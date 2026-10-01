package kr.ddingtycoon.dtledger.aggregate;

import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Confidence;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShareTextTest {

    private static TransactionRecord rec(Kind k, long amt, String cat) {
        return new TransactionRecord(0, k, amt, cat, cat, 0, true, Confidence.HIGH, true, null);
    }

    @Test
    void 오늘_정산은_순익과_상위_카테고리_3개() {
        DailyBucket b = new DailyBucket(LocalDate.of(2026, 9, 30));
        b.add(rec(Kind.INCOME, 500_000, "영혼 명품"));
        b.add(rec(Kind.INCOME, 300_000, "커피"));
        b.add(rec(Kind.INCOME, 200_000, "의뢰"));
        b.add(rec(Kind.INCOME, 100_000, "수족관"));
        b.add(rec(Kind.EXPENSE, 270_000, "각인"));
        String t = ShareText.day(b);
        assertTrue(t.contains("순익 +830,000 G"), t);
        assertTrue(t.contains("수입 상위: 영혼 명품 500,000 · 커피 300,000 · 의뢰 200,000"), t);
        assertFalse(t.contains("수족관"), "3개까지만");
        assertTrue(t.contains("지출 상위: 각인 270,000"), t);
    }

    @Test
    void 주간_정산은_날짜별_순익과_합계() {
        DailyBucket d1 = new DailyBucket(LocalDate.of(2026, 9, 30));
        d1.add(rec(Kind.INCOME, 1_000, "a"));
        DailyBucket d2 = new DailyBucket(LocalDate.of(2026, 9, 29));
        d2.add(rec(Kind.EXPENSE, 400, "b"));
        String t = ShareText.week(List.of(d1, d2));
        assertTrue(t.contains("9/30  +1,000"), t);
        assertTrue(t.contains("9/29  -400"), t);
        assertTrue(t.endsWith("합계 +600 G  (수입 1,000 / 지출 400)"), t);
    }
}
