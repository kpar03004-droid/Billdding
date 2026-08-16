package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.core.QuestRewardTracker.Entry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 의뢰 보상 감지 검증.
 * 핵심: "미수령 → 이미 완료한 의뢰" 전환을 보고 수령 시점을 직접 잡는다(금액은 창에서 확정).
 * 과거 "아무 +ΔG 채택" 방식이 판매 대금을 훔치던 사고(+93,285,292)의 회귀 방지 포함.
 */
class QuestRewardTrackerTest {

    private static Entry q(String name, long reward, boolean claimed) {
        return new Entry(name, reward, claimed);
    }

    @Test
    void lore_파싱() {
        assertEquals(20_000, QuestRewardTracker.parseRewardGold("- 보상 : 20,000골드"));
        assertEquals(0, QuestRewardTracker.parseRewardGold("- 보상 : 3루비"), "루비는 골드 아님");
        assertTrue(QuestRewardTracker.isClaimedLine("❗ 이미 완료한 의뢰입니다."));
        assertFalse(QuestRewardTracker.isClaimedLine("❗ 의뢰를 먼저 완료해주세요."));
        assertEquals("문어 채집하기 일일 의뢰",
                QuestRewardTracker.questLabel("[ 문어 채집하기 일일 의뢰 ]"));
    }

    @Test
    void 수령_전환을_보면_ΔG_없이_바로기록() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);

        t.updateGui(List.of(q("문어 채집하기 일일 의뢰", 20_000, false)));  // 아직 미수령
        assertEquals(0, out.size());

        t.updateGui(List.of(q("문어 채집하기 일일 의뢰", 20_000, true)));   // 수령!
        assertEquals(1, out.size());
        TransactionRecord r = out.get(0);
        assertEquals(TransactionRecord.Kind.INCOME, r.kind);
        assertEquals("의뢰", r.category);
        assertEquals(20_000, r.amount);
        assertEquals("문어 채집하기 일일 의뢰", r.label);
    }

    @Test
    void 수령기록후_뒤따라온_ΔG는_삼켜서_이중계상_방지() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);

        t.updateGui(List.of(q("청어 낚기 일일 의뢰", 50_000, false)));
        t.updateGui(List.of(q("청어 낚기 일일 의뢰", 50_000, true)));
        assertEquals(1, out.size());

        assertTrue(t.tryConsume(50_000), "잔고 변동은 소비하되 기록은 추가하지 않음");
        assertEquals(1, out.size(), "같은 보상이 두 번 기록되면 안 됨");
    }

    @Test
    void 이미_수령된_상태로_창을_열면_기록안함() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        // 처음 본 순간부터 '이미 완료' — 오늘 아까 받은 것이므로 지금 기록하면 안 됨
        t.updateGui(List.of(q("성게 채집하기 일일 의뢰", 20_000, true)));
        t.updateGui(List.of(q("성게 채집하기 일일 의뢰", 20_000, true)));
        assertEquals(0, out.size());
    }

    @Test
    void 여러건_연속수령도_각각_정확히() {
        // 과거엔 20,000+10,000 이 합산돼 30,000 한 건으로 잡히던 문제
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);

        t.updateGui(List.of(q("A 일일 의뢰", 20_000, false), q("B 일일 의뢰", 10_000, false)));
        t.updateGui(List.of(q("A 일일 의뢰", 20_000, true), q("B 일일 의뢰", 10_000, true)));

        assertEquals(2, out.size());
        assertEquals(30_000, out.stream().mapToLong(r -> r.amount).sum());
    }

    @Test
    void 주간의뢰도_동일하게_동작() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("광석 굴렘 처치 주간 의뢰", 100_000, false)));
        t.updateGui(List.of(q("광석 굴렘 처치 주간 의뢰", 100_000, true)));
        assertEquals(100_000, out.get(0).amount);
    }

    @Test
    void 수령감지_기록후_합산ΔG가_와도_이중계상_안됨() {
        // 감지로 2건을 기록했는데 잔고가 합쳐 한 번에 들어오는 경우(분해 로직과 충돌 방지)
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);

        t.updateGui(List.of(q("A 일일 의뢰", 20_000, false), q("B 일일 의뢰", 50_000, false)));
        t.updateGui(List.of(q("A 일일 의뢰", 20_000, true), q("B 일일 의뢰", 50_000, true)));
        assertEquals(2, out.size(), "수령 감지로 2건");

        assertTrue(t.tryConsume(70_000), "합산 잔고 변동은 삼켜야 함");
        assertEquals(2, out.size(), "이중계상되면 안 됨");
        assertEquals(70_000, out.stream().mapToLong(r -> r.amount).sum());
    }

    @Test
    void 합산된_보상은_쪼개서_각각_기록() {
        // 2026-07-29 실측: 2개 연속 수령 시 잔고가 한 번에 +70,000 으로 합쳐져
        // "의뢰 완료 70,000" 한 건으로 뭉쳤음 → 창에서 본 보상액 조합으로 분해해야 함
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("가공 시설에서 가공하기 일일 의뢰", 20_000, false),
                            q("청어 낚기 일일 의뢰", 50_000, false)));

        assertTrue(t.tryConsume(70_000));
        assertEquals(2, out.size(), "20,000 + 50,000 으로 쪼개져야 함");
        assertEquals(70_000, out.stream().mapToLong(r -> r.amount).sum());
        assertTrue(out.stream().anyMatch(r -> r.label.contains("청어")), "의뢰명이 붙어야 함");
        assertTrue(out.stream().anyMatch(r -> r.label.contains("가공")));
    }

    @Test
    void 분해_불가능하면_보상표_값만_단건기록() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("A 일일 의뢰", 20_000, false)));

        assertTrue(t.tryConsume(30_000), "보상표에 있는 값");
        assertEquals(1, out.size());
        assertEquals("의뢰 완료", out.get(0).label);
    }

    @Test
    void 판매대금은_절대_의뢰로_잡히지_않음() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("문어 채집하기 일일 의뢰", 20_000, false)));

        assertFalse(t.tryConsume(93_285_292L), "판매 대금을 의뢰 보상으로 훔치면 안 됨");
        assertEquals(0, out.size());
    }

    @Test
    void 창이_안열렸으면_ΔG로_아무것도_안잡음() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        assertFalse(t.tryConsume(20_000), "의뢰 창 없이 들어온 수입은 판매일 가능성이 큼");
        assertEquals(0, out.size());
    }

    @Test
    void 전환을_놓쳤을때만_ΔG_보조판정() {
        // 창은 봤지만 전환을 못 본 경우(스캔 사이에 수령) — 보상표 금액이면 인정
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("청어 낚기 일일 의뢰", 50_000, false)));

        assertTrue(t.tryConsume(50_000));
        assertEquals(1, out.size());
        assertEquals("청어 낚기 일일 의뢰", out.get(0).label);
    }
}
