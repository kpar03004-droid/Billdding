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
     * 업그레이드 성공 직후 화면을 계속 보고 있으면(스킬 창이 열린 채) 레벨이 바로 올라가
     * 다음 단계 가격이 표시된다. 정산은 최대 15초 뒤라, 그사이 재스캔이 맵을 덮어쓰면
     * 방금 낸 돈이 아니라 다음 단계 가격을 채택하게 된다. 신호 도착 시점 값으로 잠가야 한다.
     */
    @Test
    void 업그레이드_후_다음단계_가격으로_덮어써도_처음_낸_돈을_기록한다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 업그레이드 전: 창에 300만골(다음 단계 비용)이 떠 있다
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));

        // 업그레이드 성공 직후: 레벨이 올라 화면엔 이미 500만골(그다음 단계 비용)이 뜬다.
        // 유저가 스킬 창을 계속 보고 있어(마우스를 대고 있어) 매 스캔마다 이 값이 재확인된다.
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));
        r.tick(System.currentTimeMillis() + 1_000);   // 재스캔 시뮬레이션(정산 전)
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));

        settle(r);

        assertEquals(1, out.size());
        assertEquals(3_000_000, out.get(0).amount, "다음 단계 가격이 아니라 실제로 낸 돈이어야 함");
    }

    /**
     * 창 가격을 신호 도착 시점엔 아직 못 읽었더라도(2026-08-13 원래 취지), 이후 스캔에서
     * 처음 잡히면 그 값으로 잠기고 더 나중 스캔값(다음 단계 가격일 수 있음)은 무시해야 한다.
     */
    @Test
    void 신호_도착_직후_처음_잡힌_가격에_잠기고_그_다음값은_무시한다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 신호가 먼저 옴 — 아직 창 가격을 모른다(창을 막 연 순간 등)
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "조개 쫌 사조개 스킬", "raw", true));

        // 잠시 후 첫 스캔이 300만골을 잡음 — 이 값으로 잠겨야 한다
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 3_000_000L));
        r.tick(System.currentTimeMillis() + 1_000);

        // 그 후 다음 단계 가격(500만골)으로 화면이 바뀌어도 이미 잠긴 값을 유지해야 함
        r.noteSkillCosts(Map.of("조개 쫌 사조개", 5_000_000L));

        settle(r);

        assertEquals(1, out.size());
        assertEquals(3_000_000, out.get(0).amount, "처음 잠긴 값을 유지해야 함");
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
