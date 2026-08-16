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
        assertEquals("바다의 가호 입질 시간 감소율", r.label);
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
        assertEquals("바다의 가호 강화", out.get(0).label, "능력치 특정 불가 시 일반 라벨");
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
