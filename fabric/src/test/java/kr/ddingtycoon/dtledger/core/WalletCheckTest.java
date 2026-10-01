package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.core.TransactionRecord.Confidence;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class WalletCheckTest {
    private static final LocalDate D = LocalDate.of(2026, 9, 30);

    private static TransactionRecord rec(Kind kind, long amt, String cat, String note) {
        return new TransactionRecord(0, kind, amt, cat, "x", 0, true, Confidence.HIGH, true, note);
    }

    @Test
    void 기록과_잔고가_같이_움직이면_차이_0() {
        WalletCheck w = new WalletCheck();
        w.observe(0, 1_000_000L, D, 0, true);                // 기준
        w.observe(1_000, 1_150_000L, D, 150_000, true);      // 판매 기록 + 잔고 상승
        assertNull(w.unexplained(), "10초 안정 전엔 확정하지 않음");
        w.observe(11_000, 1_150_000L, D, 150_000, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 기록에_없는_수입은_양수로_남는다() {
        WalletCheck w = new WalletCheck();
        w.observe(0, 500_000L, D, 0, true);
        w.observe(1_000, 510_000L, D, 0, true);   // 의뢰 등 기록 없이 +10,000
        w.observe(12_000, 510_000L, D, 0, true);
        assertEquals(10_000L, w.unexplained());

        w.acknowledge(13_000);                    // 넘어감 → 기준 재설정
        assertNull(w.unexplained());
        w.observe(24_000, 510_000L, D, 0, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 처리_중에는_확정하지_않는다() {
        WalletCheck w = new WalletCheck();
        w.observe(0, 100L, D, 0, true);
        w.observe(1_000, 50L, D, 0, false);       // ΔG 왔는데 기록은 아직
        w.observe(20_000, 50L, D, 0, false);
        assertNull(w.unexplained());
        w.observe(21_000, 50L, D, -50, true);     // 기록 확정
        w.observe(32_000, 50L, D, -50, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 날짜가_바뀌면_기준을_새로_잡는다() {
        WalletCheck w = new WalletCheck();
        w.observe(0, 100L, D, 0, true);
        w.observe(1_000, 900L, D, 0, true);
        w.observe(12_000, 900L, D, 0, true);
        assertEquals(800L, w.unexplained());
        w.observe(13_000, 900L, D.plusDays(1), 0, true);
        w.observe(24_000, 900L, D.plusDays(1), 0, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 못_읽으면_대조_불가() {
        WalletCheck w = new WalletCheck();
        w.observe(0, null, D, 0, true);
        assertFalse(w.started());
        assertNull(w.unexplained());
    }

    @Test
    void 금고_안에서만_오간_돈은_잔고_영향_0() {
        assertEquals(0, WalletCheck.walletDelta(rec(Kind.INCOME, 100, TransactionRecord.CAT_FLEA_SALE, null)));
        assertEquals(0, WalletCheck.walletDelta(rec(Kind.EXPENSE, 100, TransactionRecord.CAT_FLEA_ORDER, null)));
        assertEquals(0, WalletCheck.walletDelta(VaultTracker.missedRecord(5_000, 0)));
        // 잔고↔금고 이체, 은행 입금, 손익 제외 수수료는 잔고를 실제로 움직인다
        assertEquals(-300, WalletCheck.walletDelta(rec(Kind.TRANSFER_OUT, 300, TransactionRecord.CAT_FLEA_VAULT, null)));
        assertEquals(200, WalletCheck.walletDelta(rec(Kind.TRANSFER_IN, 200, "은행", null)));
        TransactionRecord fee = rec(Kind.EXPENSE, 50, "수수료", null);
        fee.countedInPnl = false;
        assertEquals(-50, WalletCheck.walletDelta(fee));
        assertEquals(700, WalletCheck.walletDelta(rec(Kind.INCOME, 700, "플리마켓", null)), "직접 판매는 잔고로");
    }
}
