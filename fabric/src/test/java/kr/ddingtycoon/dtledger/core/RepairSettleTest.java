package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수리·품질회복 정산 규칙 — <b>창에 적힌 금액만</b> 근거로 쓴다.
 *
 * <p>2026-08-08 제보 회귀 방지:
 * <ul>
 *   <li>인챈트 없는 도구는 수리비 0원인데 <b>직전 수리비 3,638원</b>이 찍힘</li>
 *   <li>0원 수리에 <b>보유 골드 전액(1.7억)</b>이 지출로 찍힘 — ΔG 를 그대로 믿은 결과</li>
 * </ul>
 */
class RepairSettleTest {

    /** 신호 만료(15s) + 델타 대기까지 충분히 지난 시점까지 틱을 돌린다. */
    private static void settle(TransactionResolver r) {
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 16_000);
        r.tick(t0 + 32_000);
    }

    private static long repairTotal(List<TransactionRecord> out) {
        long s = 0;
        for (TransactionRecord rec : out) if ("수리".equals(rec.category)) s += rec.amount;
        return s;
    }

    @Test
    void 창이_0골드면_기록하지_않는다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 앞선 유료 수리로 3,638 을 봐 둔 상태
        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 3_638);
        // 이제 인챈트 없는 도구 — 창에 0골드
        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 0);
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        settle(r);

        assertEquals(0, repairTotal(out), "무료 수리에 직전 비용을 붙이면 안 됨");
        assertTrue(out.isEmpty());
    }

    @Test
    void 엉뚱한_거대_ΔG_는_수리비로_쓰지_않는다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 0);   // 창은 0골드라고 말하고 있다
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onDelta(-170_901_145L);                          // 잔고 오독으로 생긴 가짜 변동
        settle(r);

        assertEquals(0, repairTotal(out), "보유 골드 전액이 수리비로 박히면 안 됨");
    }

    @Test
    void 창_금액을_모르면_ΔG_가_있어도_기록하지_않는다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // noteGuiCost 없음 = 창 금액 미확보
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onDelta(-50_000_000L);
        settle(r);

        assertEquals(0, repairTotal(out), "지어내느니 빠뜨린다");
    }

    @Test
    void 정상_유료_수리는_창_금액대로_기록된다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 3_638);
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onDelta(-3_638);
        settle(r);

        assertEquals(3_638, repairTotal(out));
        assertEquals(TransactionRecord.Kind.EXPENSE, out.get(0).kind);
    }

    @Test
    void 연속_수리는_창_금액_조합으로_쪼갠다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 110_000);
        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 10_000);
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onDelta(-120_000);   // 서버가 두 건을 한 번에 반영
        settle(r);

        long sum = 0;
        for (TransactionRecord rec : out) sum += rec.amount;
        assertEquals(120_000, sum);
        assertEquals(2, out.size(), "두 건으로 나뉘어야 함");
    }

    @Test
    void 강화도_창_금액만_믿는다() {
        // 2026-08-14 제보 진단: 창은 5,000골드인데 ΔG 는 -3,220,718(644배).
        // 잔고 오독이 그대로 강화비가 되면 안 된다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.WEAPON_ENHANCE, 5_000);
        r.onSignal(new TradeSignal(TradeSignal.Type.WEAPON_ENHANCE, 0, 0, 0, "장비 강화 성공", "raw", true));
        r.onDelta(-3_220_718L);
        settle(r);

        long sum = 0;
        for (TransactionRecord rec : out) sum += rec.amount;
        assertEquals(5_000, sum, "창에 적힌 금액만 기록한다");
    }

    @Test
    void 강화는_창을_못_읽으면_기존_ΔG_경로를_그대로_쓴다() {
        // 수리와 달리 "창 아니면 기록 안 함"으로 막지 않는다 — ΔG 로 잘 동작하는 사용자가 있어
        // 창을 못 읽는 상황에서까지 버리면 손해다. 창을 읽으면 그때 창이 이긴다(위 테스트).
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.WEAPON_ENHANCE, 0, 0, 0, "장비 강화 성공", "raw", true));
        r.onDelta(-700_000L);
        settle(r);

        long sum = 0;
        for (TransactionRecord rec : out) sum += rec.amount;
        assertEquals(700_000, sum, "예전 동작이 살아 있어야 함");
    }
}
