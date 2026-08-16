package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultTrackerTest {

    private static TransactionRecord rec(TransactionRecord.Kind kind, long amount, String category) {
        return new TransactionRecord(0, kind, amount, category, "", 0, true,
                TransactionRecord.Confidence.HIGH, false, null);
    }

    @Test
    void 미설정이면_레코드_무시() {
        VaultTracker v = new VaultTracker(-1, 20_000_000, x -> {});
        assertFalse(v.isSet());
        v.onRecord(rec(TransactionRecord.Kind.INCOME, 3000, TransactionRecord.CAT_FLEA_SALE));
        assertFalse(v.isSet());
    }

    @Test
    void 판매_구매_입출금_반영() {
        VaultTracker v = new VaultTracker(-1, 20_000_000, x -> {});
        v.set(1_000_000);
        v.onRecord(rec(TransactionRecord.Kind.INCOME, 3_000, TransactionRecord.CAT_FLEA_SALE));       // +
        assertEquals(1_003_000, v.balance());
        v.onRecord(rec(TransactionRecord.Kind.EXPENSE, 500, TransactionRecord.CAT_FLEA_ORDER));       // -
        assertEquals(1_002_500, v.balance());
        v.onRecord(rec(TransactionRecord.Kind.TRANSFER_OUT, 30_000, TransactionRecord.CAT_FLEA_VAULT)); // 잔고→금고 +
        assertEquals(1_032_500, v.balance());
        v.onRecord(rec(TransactionRecord.Kind.TRANSFER_IN, 32_500, TransactionRecord.CAT_FLEA_VAULT));  // 금고→잔고 -
        assertEquals(1_000_000, v.balance());
        assertNull(v.warning());
    }

    @Test
    void 무관한_레코드는_무시() {
        VaultTracker v = new VaultTracker(500, 20_000_000, x -> {});
        v.onRecord(rec(TransactionRecord.Kind.INCOME, 10_000, "판매"));
        v.onRecord(rec(TransactionRecord.Kind.TRANSFER_OUT, 10_000, "은행"));
        assertEquals(500, v.balance());
    }

    @Test
    void 한도_초과와_음수는_클램프_및_경고() {
        VaultTracker v = new VaultTracker(19_999_000, 20_000_000, x -> {});
        v.onRecord(rec(TransactionRecord.Kind.INCOME, 5_000, TransactionRecord.CAT_FLEA_SALE));
        assertEquals(20_000_000, v.balance());
        assertNotNull(v.warning());

        v.set(100); // 재동기화 → 경고 해제
        assertNull(v.warning());
        v.onRecord(rec(TransactionRecord.Kind.EXPENSE, 500, TransactionRecord.CAT_FLEA_ORDER));
        assertEquals(0, v.balance());
        assertNotNull(v.warning());
    }

    @Test
    void 설정은_한도로_클램프_및_저장콜백() {
        long[] saved = {-2};
        VaultTracker v = new VaultTracker(-1, 20_000_000, x -> saved[0] = x);
        v.set(99_999_999);
        assertEquals(20_000_000, v.balance());
        assertEquals(20_000_000, saved[0]);
        assertTrue(v.isSet());
    }
}
