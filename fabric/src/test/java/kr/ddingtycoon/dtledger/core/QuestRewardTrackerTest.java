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

    // ───────── 2026-09-28 제보: 의뢰 ×3 중복 · 하지 않은 의뢰가 완료로 찍힘 ─────────

    @Test
    void 같은_보상액_두건_수령후_합산ΔG는_삼킨다() {
        // 레드스톤 50,000 + 청금석 50,000 → 잔고 +100,000. 예전엔 레드스톤 ×3 으로 기록됐다.
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("레드스톤 채광하기 일일 의뢰", 50_000, false), q("청금석 채광하기 일일 의뢰", 50_000, false)));
        t.updateGui(List.of(q("레드스톤 채광하기 일일 의뢰", 50_000, true), q("청금석 채광하기 일일 의뢰", 50_000, true)));
        assertEquals(2, out.size(), "수령 감지로 2건");

        assertTrue(t.tryConsume(100_000), "합산 잔고 변동은 삼켜야 함");
        assertEquals(2, out.size(), "레드스톤이 더 찍히면 안 됨");
        assertEquals(1, out.stream().filter(r -> r.label.contains("레드스톤")).count());
        assertEquals(1, out.stream().filter(r -> r.label.contains("청금석")).count());
    }

    @Test
    void 같은_보상액_두건_수령후_ΔG가_따로와도_각각_삼킨다() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("레드스톤 채광하기 일일 의뢰", 20_000, false), q("청금석 채광하기 일일 의뢰", 20_000, false)));
        t.updateGui(List.of(q("레드스톤 채광하기 일일 의뢰", 20_000, true), q("청금석 채광하기 일일 의뢰", 20_000, true)));

        assertTrue(t.tryConsume(20_000));
        assertTrue(t.tryConsume(20_000), "두 번째 20,000 도 이미 기록된 것 — 삼켜야 함");
        assertEquals(2, out.size());
    }

    @Test
    void 수령기록이_남아있는데_설명안되는_ΔG는_의뢰로_또_적지_않는다() {
        // 금 10,000 을 받아 수령 감지로 적은 직후, 창에 굴 10,000(안 한 의뢰)이 떠 있는 상태에서
        // 조합이 안 맞는 잔고 변동이 오면 그걸 의뢰로 만들지 않는다.
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("금 채광하기 일일 의뢰", 10_000, false), q("굴 채집하기 일일 의뢰", 10_000, false)));
        t.updateGui(List.of(q("금 채광하기 일일 의뢰", 10_000, true), q("굴 채집하기 일일 의뢰", 10_000, false)));
        assertEquals(1, out.size());

        assertFalse(t.tryConsume(30_000), "보상표 값이어도 수령 기록과 안 맞으면 의뢰가 아님");
        assertEquals(1, out.size());
        assertTrue(out.stream().noneMatch(r -> r.label.contains("굴")), "안 한 의뢰 이름이 찍히면 안 됨");
    }

    @Test
    void 같은_보상액_의뢰가_둘이면_이름을_추측하지_않는다() {
        // 전환을 놓친 경우의 보조 판정 — 금·굴 둘 다 10,000 이면 어느 쪽인지 모른다.
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("금 채광하기 일일 의뢰", 10_000, false), q("굴 채집하기 일일 의뢰", 10_000, false)));

        assertTrue(t.tryConsume(10_000));
        assertEquals(1, out.size());
        assertEquals("의뢰 완료", out.get(0).label, "굴/금 중 아무거나 붙이면 안 됨");
    }

    @Test
    void 이미_받은_의뢰는_보조판정_후보가_아니다() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("금 채광하기 일일 의뢰", 10_000, true), q("굴 채집하기 일일 의뢰", 10_000, false)));

        assertTrue(t.tryConsume(10_000));
        assertEquals("굴 채집하기 일일 의뢰", out.get(0).label, "이미 받은 '금'은 다시 돈을 줄 수 없다");
    }

    @Test
    void 진행도_파싱() {
        assertEquals(Boolean.FALSE, QuestRewardTracker.parseProgressDone("- 의뢰 진행도 : 0 / 30"));
        assertEquals(Boolean.FALSE, QuestRewardTracker.parseProgressDone("- 의뢰 진행도 : 29 / 30"));
        assertEquals(Boolean.TRUE, QuestRewardTracker.parseProgressDone("- 의뢰 진행도 : 30 / 30"));
        assertEquals(Boolean.TRUE, QuestRewardTracker.parseProgressDone("- 의뢰 진행도 : 1,200 / 1,000"));
        assertEquals(null, QuestRewardTracker.parseProgressDone("- 보상 : 10,000골드"));
    }

    @Test
    void 진행도가_안찬_의뢰만_있으면_ΔG를_의뢰로_안본다() {
        // 2026-09-28 제보(2번째 사람): 의뢰를 하나도 안 했는데 창에 떠 있던 굴 채집 10,000 으로
        // 무관한 +10,000 이 의뢰 수입으로 기록됨. 진행도 0/30 인 의뢰는 돈을 줄 수 없다.
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(new Entry("굴 채집하기 일일 의뢰", 10_000, false, false)));

        assertFalse(t.tryConsume(10_000), "받을 수 있는 의뢰가 없으니 의뢰 수입이 아님");
        assertFalse(t.tryConsume(30_000), "보상표 안전망(흔한 금액)도 같이 막혀야 함");
        assertEquals(0, out.size());
    }

    @Test
    void 진행도가_찬_의뢰가_있으면_보조판정은_그대로() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(new Entry("굴 채집하기 일일 의뢰", 10_000, false, false),
                            new Entry("금 채광하기 일일 의뢰", 10_000, false, true)));

        assertTrue(t.tryConsume(10_000));
        assertEquals(1, out.size());
        assertEquals("금 채광하기 일일 의뢰", out.get(0).label, "진행 중인 굴이 아니라 다 채운 금이어야 함");
    }

    @Test
    void ΔG가_수령전환보다_먼저와도_한번만_기록() {
        List<TransactionRecord> out = new ArrayList<>();
        QuestRewardTracker t = new QuestRewardTracker(out::add);
        t.updateGui(List.of(q("청어 낚기 일일 의뢰", 50_000, false)));

        assertTrue(t.tryConsume(50_000));                                   // 잔고가 먼저 갱신
        t.updateGui(List.of(q("청어 낚기 일일 의뢰", 50_000, true)));        // 그 다음 창에 '이미 완료'
        assertEquals(1, out.size(), "두 경로가 같은 보상을 두 번 적으면 안 됨");
    }
}
