package kr.ddingtycoon.dtledger.aggregate;

import kr.ddingtycoon.dtledger.core.SaleCategory;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.util.GoldFormat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 분야(카테고리) 하나의 기간 수익 — 오늘 탭에서 카테고리 줄을 누르면 여는 상세 화면용.
 *
 * <p>품목 기준: 유저상점·플리마켓에서 판 물건도 품목 이름으로 분야를 다시 매긴다.
 * 저장 카테고리는 판매처라서("유저상점") 사냥 전리품을 유저상점에 팔면 사냥에 안 잡히기 때문이다.
 * 저장된 기록은 바꾸지 않고 보는 방식만 바꾼다. MC 의존성 0.
 */
public final class CategoryView {
    private CategoryView() {}

    /** 판매처 카테고리 — 품목 기준으로 보면 품목 이름으로 다시 분류한다. */
    public static final Set<String> VENUES = Set.of("유저상점", "플리마켓", TransactionRecord.CAT_FLEA_SALE);

    public record Item(String label, long amount, int count) {}

    public static final int LIST_MAX = 10;

    /**
     * @param byDay 기간의 날짜별 [수입, 지출] (오래된 날 먼저, 빈 날 포함)
     * @param top      번 것 — 수입이 큰 품목 순(최대 {@link #LIST_MAX}개)
     * @param spent    쓴 것 — 지출이 큰 항목 순(최대 {@link #LIST_MAX}개)
     */
    public record Result(String category, LocalDate from, LocalDate to, long income, long expense,
                         int count, Map<LocalDate, long[]> byDay, List<Item> top, List<Item> spent) {
        public long net() { return income - expense; }
        public int days() { return byDay.size(); }
    }

    /** 이 레코드가 보기 방식에서 속하는 분야. 손익에 안 들어가는 기록(이체 등)은 null. */
    public static String categoryOf(TransactionRecord r, boolean byItem) {
        if (r == null || !r.countedInPnl || r.isTransfer()) return null;
        String cat = r.category == null || r.category.isEmpty() ? "기타" : r.category;
        if (byItem && r.kind == TransactionRecord.Kind.INCOME && VENUES.contains(cat)
                && r.label != null && !r.label.isBlank()) {
            String item = SaleCategory.of(r.label, r.label);
            if (!"판매".equals(item)) return item; // 모르는 품목이면 판매처 그대로
        }
        return cat;
    }

    /**
     * @param records 기간을 덮는 레코드(기간 밖이 섞여 있어도 된다)
     * @param dateOf  레코드 → 장부 날짜(리셋 시각 반영)
     */
    public static Result of(List<TransactionRecord> records, String category, boolean byItem,
                            LocalDate from, LocalDate to, Function<TransactionRecord, LocalDate> dateOf) {
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) byDay.put(d, new long[2]);
        Map<String, long[]> items = new LinkedHashMap<>(); // 번 것: 라벨 → [금액, 건수]
        Map<String, long[]> spentItems = new LinkedHashMap<>(); // 쓴 것
        long in = 0, out = 0;
        int count = 0;
        for (TransactionRecord r : records) {
            if (!category.equals(categoryOf(r, byItem))) continue;
            long[] day = byDay.get(dateOf.apply(r));
            if (day == null) continue;
            count++;
            boolean income = r.kind == TransactionRecord.Kind.INCOME;
            if (income) {
                day[0] += r.amount;
                in += r.amount;
            } else {
                day[1] += r.amount;
                out += r.amount;
            }
            String label = r.label == null || r.label.isBlank() ? category : r.label;
            long[] it = (income ? items : spentItems).computeIfAbsent(label, k -> new long[2]);
            it[0] += r.amount;
            it[1]++;
        }
        return new Result(category, from, to, in, out, count, byDay, ranked(items), ranked(spentItems));
    }

    private static List<Item> ranked(Map<String, long[]> items) {
        List<Item> out = new ArrayList<>();
        items.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(LIST_MAX)
                .forEach(e -> out.add(new Item(e.getKey(), e.getValue()[0], (int) e.getValue()[1])));
        return out;
    }

    /** 기간 안에 등장한 분야를 수입 큰 순으로 — 상세 화면의 ◀ ▶ 분야 넘기기용. */
    public static List<String> categories(List<TransactionRecord> records, boolean byItem,
                                          LocalDate from, LocalDate to,
                                          Function<TransactionRecord, LocalDate> dateOf) {
        Map<String, Long> sum = new LinkedHashMap<>();
        for (TransactionRecord r : records) {
            String c = categoryOf(r, byItem);
            if (c == null) continue;
            LocalDate d = dateOf.apply(r);
            if (d.isBefore(from) || d.isAfter(to)) continue;
            sum.merge(c, r.kind == TransactionRecord.Kind.INCOME ? r.amount : 0, Long::sum);
        }
        List<String> out = new ArrayList<>(sum.keySet());
        out.sort((a, b) -> Long.compare(sum.get(b), sum.get(a)));
        return out;
    }

    /**
     * 기간의 분야별 [수입, 지출] — 통계 목록 화면용.
     * 수입 큰 순, 수입이 없는 분야는 그 뒤에 지출 큰 순.
     */
    public static List<Map.Entry<String, long[]>> totals(List<TransactionRecord> records, boolean byItem,
                                                         LocalDate from, LocalDate to,
                                                         Function<TransactionRecord, LocalDate> dateOf) {
        Map<String, long[]> sum = new LinkedHashMap<>();
        for (TransactionRecord r : records) {
            String c = categoryOf(r, byItem);
            if (c == null) continue;
            LocalDate d = dateOf.apply(r);
            if (d.isBefore(from) || d.isAfter(to)) continue;
            sum.computeIfAbsent(c, k -> new long[2])[r.kind == TransactionRecord.Kind.INCOME ? 0 : 1] += r.amount;
        }
        List<Map.Entry<String, long[]>> out = new ArrayList<>(sum.entrySet());
        out.sort((a, b) -> a.getValue()[0] != b.getValue()[0]
                ? Long.compare(b.getValue()[0], a.getValue()[0])
                : Long.compare(b.getValue()[1], a.getValue()[1]));
        return out;
    }

    /** 통계 목록의 디스코드용 평문. */
    public static String shareList(List<Map.Entry<String, long[]>> rows, LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder("📒 빌띵 · 분야별 · ")
                .append(from.equals(to) ? from.toString() : from + " ~ " + to).append('\n');
        long in = 0, out = 0;
        for (var e : rows) {
            in += e.getValue()[0];
            out += e.getValue()[1];
        }
        sb.append("순익 ").append(GoldFormat.signed(in - out)).append(" G  (수입 ")
                .append(GoldFormat.format(in)).append(" / 지출 ").append(GoldFormat.format(out)).append(")\n");
        for (var e : rows) {
            long[] v = e.getValue();
            sb.append("· ").append(e.getKey());
            if (v[0] > 0) sb.append("  +").append(GoldFormat.format(v[0]));
            if (v[1] > 0) sb.append("  -").append(GoldFormat.format(v[1]));
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** 디스코드용 평문. */
    public static String share(Result r) {
        StringBuilder sb = new StringBuilder("📒 빌띵 · ").append(r.category()).append(" · ")
                .append(r.from().equals(r.to()) ? r.from().toString() : r.from() + " ~ " + r.to())
                .append('\n');
        sb.append("순익 ").append(GoldFormat.signed(r.net())).append(" G  (수입 ")
                .append(GoldFormat.format(r.income())).append(" / 지출 ")
                .append(GoldFormat.format(r.expense())).append(")\n");
        list(sb, "번 것", r.top());
        list(sb, "쓴 것", r.spent());
        return sb.toString().stripTrailing();
    }

    private static void list(StringBuilder sb, String title, List<Item> items) {
        if (items.isEmpty()) return;
        sb.append(title).append('\n');
        for (Item it : items.subList(0, Math.min(5, items.size()))) {
            sb.append("· ").append(it.label()).append(' ').append(GoldFormat.format(it.amount()))
                    .append(" (").append(it.count()).append("건)\n");
        }
    }
}
