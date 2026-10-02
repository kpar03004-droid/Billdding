package kr.ddingtycoon.dtledger.aggregate;

import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Confidence;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CategoryViewTest {
    private static final LocalDate D = LocalDate.of(2026, 10, 2);

    /** timestamp 자리에 "며칠 전"을 넣고 dateOf 로 풀어 쓴다 — 시간대와 무관한 테스트. */
    private static TransactionRecord rec(int daysAgo, Kind k, long amt, String cat, String label) {
        return new TransactionRecord(daysAgo, k, amt, cat, label, 1, true, Confidence.HIGH, true, null);
    }

    private static final Function<TransactionRecord, LocalDate> DATE = r -> D.minusDays(r.timestamp);

    private static final List<TransactionRecord> RECS = List.of(
            rec(0, Kind.INCOME, 100, "사냥전문가", "평범한 표범"),
            rec(2, Kind.INCOME, 300, "사냥전문가", "평범한 표범"),
            rec(2, Kind.INCOME, 50, "사냥전문가", "사슴 뿔"),
            rec(1, Kind.INCOME, 1_000, "유저상점", "특이한 가죽 파우치"),
            rec(1, Kind.EXPENSE, 70, "사냥전문가", "x"),
            rec(5, Kind.INCOME, 9_999, "사냥전문가", "기간 밖"),
            rec(2, Kind.TRANSFER_IN, 5_000, "은행", "은행 출금"));

    @Test
    void 이틀_전_하루만() {
        var r = CategoryView.of(RECS, "사냥전문가", false, D.minusDays(2), D.minusDays(2), DATE);
        assertEquals(350, r.income());
        assertEquals(1, r.days());
        assertEquals("평범한 표범", r.top().get(0).label());
        assertEquals(300, r.top().get(0).amount());
    }

    @Test
    void 사흘_동안_합계와_날짜별() {
        var r = CategoryView.of(RECS, "사냥전문가", false, D.minusDays(2), D, DATE);
        assertEquals(450, r.income());
        assertEquals(70, r.expense());
        assertEquals(380, r.net());
        assertEquals(3, r.days());
        assertEquals(350, r.byDay().get(D.minusDays(2))[0]);
        assertEquals(70, r.byDay().get(D.minusDays(1))[1]);
        assertEquals(400, r.top().get(0).amount(), "같은 품목은 합친다");
        assertEquals(2, r.top().get(0).count());
        assertEquals(1, r.spent().size(), "쓴 것은 따로");
        assertEquals("x", r.spent().get(0).label());
        assertEquals(70, r.spent().get(0).amount());
    }

    @Test
    void 품목_기준이면_유저상점_판매도_품목_분야로() {
        assertEquals("유저상점", CategoryView.categoryOf(RECS.get(3), false));
        assertEquals("영혼 명품", CategoryView.categoryOf(RECS.get(3), true));
        var r = CategoryView.of(RECS, "영혼 명품", true, D.minusDays(6), D, DATE);
        assertEquals(1_000, r.income());
        assertNull(CategoryView.categoryOf(RECS.get(6), true), "이체는 분야 수익 아님");
    }

    @Test
    void 분야_목록은_수입_큰_순() {
        // 사냥 450 + 5일 전 9,999 > 영혼 명품 1,000
        assertEquals(List.of("사냥전문가", "영혼 명품"), CategoryView.categories(RECS, true, D.minusDays(6), D, DATE));
        // 사흘만 보면 5일 전이 빠져 영혼 명품(1,000)이 사냥(450)보다 앞
        assertEquals(List.of("영혼 명품", "사냥전문가"), CategoryView.categories(RECS, true, D.minusDays(2), D, DATE));
    }

    @Test
    void 통계_목록은_분야별_수입과_지출() {
        var rows = CategoryView.totals(RECS, true, D.minusDays(2), D, DATE);
        assertEquals("영혼 명품", rows.get(0).getKey());
        assertArrayEquals(new long[]{1_000, 0}, rows.get(0).getValue());
        assertEquals("사냥전문가", rows.get(1).getKey());
        assertArrayEquals(new long[]{450, 70}, rows.get(1).getValue());
        assertEquals(2, rows.size(), "이체·기간 밖은 빠진다");
        String t = CategoryView.shareList(rows, D.minusDays(2), D);
        assertTrue(t.contains("순익 +1,380 G"), t);
        assertTrue(t.contains("· 사냥전문가  +450  -70"), t);
    }

    @Test
    void 복사문() {
        var r = CategoryView.of(RECS, "사냥전문가", false, D.minusDays(2), D, DATE);
        String t = CategoryView.share(r);
        assertTrue(t.startsWith("📒 빌띵 · 사냥전문가 · 2026-09-30 ~ 2026-10-02"), t);
        assertTrue(t.contains("번 것\n· 평범한 표범 400 (2건)"), t);
        assertTrue(t.contains("쓴 것\n· x 70 (1건)"), t);
    }
}
