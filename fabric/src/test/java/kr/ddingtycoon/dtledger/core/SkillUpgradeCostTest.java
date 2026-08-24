package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 전문가 스킬 업그레이드 비용을 창에서 읽는 경로 검증.
 *
 * <p>2026-08-13 제보: 창을 열어둔 채 시간이 지나면 골드 표시가 갱신되지 않아 ΔG 가 안 오고,
 * 15초 대기창을 넘겨 지출이 <b>통째로 누락</b>됐다(유저가 수동으로 추가함).
 */
class SkillUpgradeCostTest {

    // ── lore 파싱 ──

    @Test
    void 소모_재화_줄에서_금액을_읽는다() {
        assertEquals(100_000L, SkillCostLore.parseCost("  - 소모 재화 : 100,000골드"));
        assertEquals(1_000_000L, SkillCostLore.parseCost("- 소모 재화: 1,000,000 골드"));
        assertNull(SkillCostLore.parseCost("  - 소모 스킬 포인트 : 10포인트"), "포인트는 골드가 아님");
        assertNull(SkillCostLore.parseCost("  - 소모 아이템 : 어빌리티 스톤 20개"));
        assertNull(SkillCostLore.parseCost(null));
    }

    @Test
    void 전문가_스킬_창은_소모_스킬_포인트로_구분한다() {
        // "소모 재화"는 바다의 가호 창에도 있다 — 이 줄이 있어야 전문가 스킬로 인정
        assertTrue(SkillCostLore.isSignature("  - 소모 스킬 포인트 : 10포인트"));
        assertEquals(false, SkillCostLore.isSignature("  - 소모 재화 : 60,000골드"),
                "바다의 가호 창을 전문가 스킬로 오인하면 안 됨");
    }

    @Test
    void 아이템_이름에서_스킬_이름만_뽑는다() {
        assertEquals("재배학개론", SkillCostLore.skillName("[세레니티] 재배학개론 (+3)"));
        assertEquals("재배학개론", SkillCostLore.skillName("§a[세레니티] 재배학개론 (+3)"));
        assertEquals("귀하신 몸값", SkillCostLore.skillName("[세레니티] 귀하신 몸값 (+1)"));
        assertEquals("조개 무한리필", SkillCostLore.skillName("조개 무한리필"));
    }

    @Test
    void 채팅_라벨과_스킬_이름을_짝짓는다() {
        // 파서는 "{이름} 스킬" / 실패 시 "{이름} 스킬 실패" 로 라벨을 만든다
        assertTrue(SkillCostLore.matchesLabel("재배학개론", "재배학개론 스킬"));
        assertTrue(SkillCostLore.matchesLabel("재배학개론", "재배학개론 스킬 실패"));
        assertEquals(false, SkillCostLore.matchesLabel("재배학개론", "채광학개론 스킬"));
        assertEquals(false, SkillCostLore.matchesLabel("", "재배학개론 스킬"));
    }

    // ── 정산 ──

    private static void settle(TransactionResolver r) {
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 16_000);
        r.tick(t0 + 32_000);
    }

    private static long sum(List<TransactionRecord> out) {
        long s = 0;
        for (TransactionRecord r : out) s += r.amount;
        return s;
    }

    @Test
    void ΔG_가_안_와도_창_금액으로_기록한다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteSkillCosts(Map.of("재배학개론", 100_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        settle(r);   // ΔG 를 한 번도 주지 않는다 — 예전엔 여기서 통째로 누락됐다

        assertEquals(1, out.size(), "누락되면 안 됨");
        assertEquals(100_000, out.get(0).amount);
        assertEquals(TransactionRecord.Kind.EXPENSE, out.get(0).kind);
        assertEquals("전문가", out.get(0).category);
    }

    @Test
    void 여러_스킬이_떠_있어도_이름으로_정확히_고른다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 스킬 트리에는 노드가 여러 개 떠 있다
        r.noteSkillCosts(Map.of("재배학개론", 100_000L, "귀하신 몸값", 500_000L, "조개 무한리필", 20_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "귀하신 몸값 스킬", "raw", true));
        settle(r);

        assertEquals(1, out.size());
        assertEquals(500_000, out.get(0).amount, "다른 스킬 금액을 가져오면 안 됨");
    }

    @Test
    void 실패한_업그레이드도_비용은_나간다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteSkillCosts(Map.of("재배학개론", 100_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬 실패", "raw", true));
        settle(r);

        assertEquals(100_000, sum(out));
    }

    @Test
    void 연속_업그레이드는_각각_기록된다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteSkillCosts(Map.of("재배학개론", 100_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        settle(r);

        assertEquals(2, out.size());
        assertEquals(200_000, sum(out));
    }

    /**
     * 2026-08-22 제보: "전문가 300만골짜리를 했는데 5백만골 지출되어 있다".
     *
     * <p>실측 타임라인 — <b>누르는 순간</b> 레벨이 올라 창엔 이미 다음 단계 가격이 뜨고,
     * "업그레이드 성공" 채팅도 거의 <b>동시에</b> 온다. 돈은 그보다 더 뒤에 빠진다.
     * 즉 채팅이 도착했을 땐 표시가가 이미 오염돼 있어, 그 시점 값을 믿으면 안 된다.
     */
    @Test
    void 클릭_순간_가격이_바뀌고_채팅이_동시에_와도_실제_낸_돈을_기록한다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 마우스를 올려둔 상태 — 창에 현재 단계 비용 300만골이 떠 있고 스캔이 이를 읽는다
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));

        // 클릭! 레벨이 즉시 올라 창은 다음 단계 500만골로 바뀐다(스캔이 이걸 먼저 잡을 수도 있다)
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));
        // 그와 거의 동시에 성공 채팅 도착 — 이 시점 표시가는 이미 500만골이다
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));
        // 계속 마우스를 대고 있어 같은 값이 반복 스캔된다
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));

        settle(r);

        assertEquals(1, out.size());
        assertEquals(3_000_000, out.get(0).amount, "다음 단계 가격이 아니라 실제로 낸 돈이어야 함");
    }

    @Test
    void 연속_업그레이드는_각_단계의_실제_지불액으로_쪼갠다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 100만 → 300만 → 500만 으로 두 번 올린다. 실제 지불액은 100만 + 300만.
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 1_000_000L));
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));

        settle(r);

        assertEquals(2, out.size());
        assertEquals(4_000_000, sum(out), "100만+300만 이어야 함(500만이 섞이면 안 됨)");
    }

    @Test
    void 가격이_안_바뀌면_현재_표시가를_쓴다() {
        // 최고 레벨 등으로 표시가가 그대로인 경우 — 전환 기록이 없으니 현재가로 대체
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));

        settle(r);

        assertEquals(3_000_000, sum(out));
    }

    @Test
    void 다른_스킬의_가격_변동에_영향받지_않는다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L, "심해 채집꾼", 1_000_000L));
        // 심해 채집꾼만 업그레이드 — 조개 쫌 사조개 가격은 그대로
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L, "심해 채집꾼", 2_000_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "심해 채집꾼 스킬", "raw", true));

        settle(r);

        assertEquals(1, out.size());
        assertEquals(1_000_000, out.get(0).amount, "심해 채집꾼의 직전가여야 함");
    }

    @Test
    void 창을_못_읽었으면_기존_ΔG_경로로_넘어간다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // noteSkillCosts 없음 — 창을 안 열었거나 문구가 바뀐 경우
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        r.onDelta(-100_000);
        settle(r);

        assertEquals(100_000, sum(out), "예전 동작(ΔG)도 그대로 살아 있어야 함");
    }
}
