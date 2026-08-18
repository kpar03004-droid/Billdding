package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 바다의 가호 지출 감지 검증.
 * 창 제목이 아니라 강화창 아이템 lore("소모 재화 : N골드")로 판별·금액 확정하는 방식.
 */
class SeaBlessingTrackerTest {

    @Test
    void lore에서_소모골드_파싱() {
        assertEquals(60_000, SeaBlessingTracker.parseCostGold("- 소모 재화 : 60,000골드"));
        assertEquals(5_000, SeaBlessingTracker.parseCostGold("소모 재화 : 5,000 골드"));
        assertEquals(0, SeaBlessingTracker.parseCostGold("- 소모 아이템 : 오로라 조각 2개"));
        assertEquals(0, SeaBlessingTracker.parseCostGold(null));
    }

    @Test
    void 능력치명_정리() {
        assertEquals("입질 시간 감소율", SeaBlessingTracker.abilityLabel("[경기] 입질 시간 감소율 (+5)"));
        assertEquals("골드 보상 정산 보정", SeaBlessingTracker.abilityLabel("[결과] 골드 보상 정산 보정 (+0)"));
        assertEquals("타이브레이커 포인트", SeaBlessingTracker.abilityLabel("[결과] 타이브레이커 포인트 (+4)"));
    }

    @Test
    void 비용표_단계_매핑() { // 툴팁 실측과 일치: (+0)→5,000=1강, (+4)→30,000=5강, (+5)→60,000=6강
        assertEquals(1, SeaBlessingTracker.stageOfCost(5_000));
        assertEquals(5, SeaBlessingTracker.stageOfCost(30_000));
        assertEquals(6, SeaBlessingTracker.stageOfCost(60_000));
        assertEquals(7, SeaBlessingTracker.stageOfCost(100_000));
        assertEquals(30, SeaBlessingTracker.stageOfCost(5_000_000));
        assertEquals(0, SeaBlessingTracker.stageOfCost(123_456));
    }

    @Test
    void 창에서_읽은_비용과_일치하면_능력치명까지_기록() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.updateGui(Map.of(60_000L, "입질 시간 감소율", 5_000L, "피버 타임 지속 시간"));

        assertTrue(t.tryConsume(-60_000));
        assertEquals(1, out.size());
        TransactionRecord r = out.get(0);
        assertEquals(TransactionRecord.Kind.EXPENSE, r.kind);
        assertEquals("바다의 가호", r.category);
        assertEquals(60_000, r.amount);
        assertEquals("입질 시간 감소율", r.label, "카테고리가 이미 바다의 가호라 라벨엔 능력치명만");
    }

    @Test
    void 강화후_표시비용이_바뀌어도_직전비용_인식() {
        // 강화 성공하면 창의 표시 비용이 다음 단계로 바뀜 → 직전에 본 비용도 일정 시간 유효해야 함
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.updateGui(Map.of(60_000L, "입질 시간 감소율"));   // 강화 전 표시
        t.updateGui(Map.of(100_000L, "입질 시간 감소율"));  // 강화 후 표시(다음 단계)

        assertTrue(t.tryConsume(-60_000), "실제 빠져나간 건 직전 단계 비용");
        assertEquals(60_000, out.get(0).amount);
    }

    @Test
    void 창을_못읽어도_비용표에_있으면_보조판정() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.updateGui(Map.of(5_000L, "피버 타임 지속 시간")); // 창은 확인됨
        assertTrue(t.tryConsume(-780_000), "비용표(15강)에 있는 금액이면 인정");
        assertEquals("강화", out.get(0).label, "능력치 특정 불가 시 일반 라벨");
    }

    // ── 창 단계 상승으로 강화 감지(ΔG 불가 서버) ──

    @Test
    void 단계_표기_파싱() {
        assertEquals(5, SeaBlessingTracker.parseLevel("[경기] 입질 시간 감소율 (+5)"));
        assertEquals(0, SeaBlessingTracker.parseLevel("[결과] 골드 보상 정산 보정 (+0)"));
        assertEquals(-1, SeaBlessingTracker.parseLevel("소모 재화 : 20,000골드"));
        assertEquals(-1, SeaBlessingTracker.parseLevel(null));
    }

    @Test
    void 창_단계가_오르면_그_비용을_지출로_기록() {
        // 2026-08-18 실측: "[경기] 일타쌍피 발동 확률 (+3)" 소모 재화 20,000골드.
        // 잔고를 못 읽는 서버라 ΔG 없이 단계 상승만으로 잡아야 한다.
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);

        // 창을 처음 봄 — 기준선만 잡고 기록하지 않는다
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("일타쌍피 발동 확률", 3, 20_000)));
        assertTrue(out.isEmpty(), "창을 열어본 것만으로는 지출이 아니다");

        // 쉬프트클릭으로 강화 → 다음 틱 단계가 +4, 비용은 다음 단계로 바뀜
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("일타쌍피 발동 확률", 4, 30_000)));
        assertEquals(1, out.size(), "단계가 올랐으면 강화한 것");
        TransactionRecord r = out.get(0);
        assertEquals(20_000, r.amount, "지불액은 올라가기 전 단계에서 보던 비용");
        assertEquals("일타쌍피 발동 확률", r.label);
        assertEquals(TransactionRecord.Kind.EXPENSE, r.kind);
    }

    @Test
    void 단계가_그대로면_기록하지_않는다() {
        // 창을 열어 여러 능력치를 구경만 해도 지출이 생기면 안 된다
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        var snap = List.of(
                new SeaBlessingTracker.Ability("입질 시간 감소율", 5, 60_000),
                new SeaBlessingTracker.Ability("일타쌍피 발동 확률", 3, 20_000));
        t.noteWindow(snap);
        t.noteWindow(snap);
        t.noteWindow(snap);
        assertTrue(out.isEmpty(), "단계 변화 없음 = 지출 없음");
    }

    @Test
    void 여러단계_한번에_오르면_단계표로_합산() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("피버 타임 지속 시간", 3, 20_000)));
        // +3 → +6 (틱 사이 3연속 강화). 4·5·6강 비용 합 = 20,000+30,000+60,000
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("피버 타임 지속 시간", 6, 100_000)));
        assertEquals(110_000, out.get(0).amount);
    }

    @Test
    void 창단계로_기록한_뒤_같은_ΔG는_이중계상_안함() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("일타쌍피 발동 확률", 3, 20_000)));
        t.noteWindow(List.of(new SeaBlessingTracker.Ability("일타쌍피 발동 확률", 4, 30_000)));
        assertEquals(1, out.size());
        // 만약 (다른 서버에서) ΔG 가 뒤늦게 같은 금액으로 와도 또 세지 않는다
        assertFalse(t.tryConsume(-20_000), "방금 창으로 기록한 금액은 ΔG 로 재기록 금지");
        assertEquals(1, out.size());
    }

    @Test
    void 창_안열렸으면_소비안함() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        assertFalse(t.tryConsume(-60_000));
        assertEquals(0, out.size());
    }

    @Test
    void 창열림이라도_무관한금액_골드증가는_소비안함() {
        List<TransactionRecord> out = new ArrayList<>();
        SeaBlessingTracker t = new SeaBlessingTracker(out::add);
        t.updateGui(Map.of(60_000L, "입질 시간 감소율"));
        assertFalse(t.tryConsume(-123_456), "비용표에도 창에도 없는 금액");
        assertFalse(t.tryConsume(60_000), "골드 증가는 강화 지출 아님");
        assertEquals(0, out.size());
    }
}
