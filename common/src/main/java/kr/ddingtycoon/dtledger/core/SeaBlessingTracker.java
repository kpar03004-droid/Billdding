package kr.ddingtycoon.dtledger.core;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 바다의 가호(파라다이스 낚시대회 강화) 지출 감지.
 *
 * 채팅 메시지가 전혀 없어(실측 확인) "채팅 앵커 → ΔG" 방식이 불가능하다.
 * 대신 강화창 자체를 근거로 삼는다 — 창에 놓인 능력치 아이템의 설명(lore)에
 * "소모 재화 : 60,000골드"가 그대로 적혀 있으므로, 창을 읽으면
 *   (1) 여기가 바다의 가호 창인지  (2) 각 능력치의 정확한 소모 골드
 * 를 둘 다 알 수 있다. 창 제목은 커스텀 리소스팩이라 신뢰할 수 없어 쓰지 않는다.
 *
 * 동작:
 *   - 로더의 화면 감시기가 매 틱 {@link #updateGui}로 (소모골드 → 능력치명)을 넘긴다.
 *   - 골드 감소(ΔG<0)가 창 활성 중 발생하고 그 금액이 창에서 읽은 비용과 일치하면
 *     "바다의 가호 {능력치}" 지출로 기록하고 소비(true). 아니면 false(기존 경로로 넘김).
 *   - 강화 직후 창의 표시 비용은 다음 단계로 바뀌므로 최근 본 비용을 일정 시간 누적 보관한다.
 *   - 창에서 못 읽은 경우를 대비해 위키 비용표(1~30강)를 보조 판정으로 둔다.
 *
 * 오로라 조각·어빌리티 스톤도 함께 소모되지만 골드가 아니므로 집계 대상이 아니다.
 * MC 의존성 0 — 오프라인 단위테스트 가능.
 */
public final class SeaBlessingTracker {

    /** 창을 닫은 직후에도 ΔG(잔고 표시 갱신 지연)를 잡기 위한 유예. */
    // 지출 쪽이라(음수 ΔG) 다른 거래와 겹칠 위험이 낮아, 강화창을 오래 열어둬도 놓치지 않게 넉넉히 둔다.
    private static final long GRACE_MS = 60_000;
    /** 강화 성공 시 표시 비용이 다음 단계로 바뀌므로, 직전에 본 비용도 이만큼 유효 취급. */
    private static final long COST_MEMORY_MS = 120_000;

    /** 이 창임을 알리는 lore 서명(능력치 설명 머리말). */
    public static final String GUI_SIGNATURE = "낚시 능력치";

    private static final Pattern COST_LINE = Pattern.compile("소모\\s*재화\\s*[:：]\\s*([\\d,]+)\\s*골드");

    /** 골드 비용 → 강화 단계. 위키 강화-요소 문서(2026-07-22). 30개 값 전부 상이. */
    private static final Map<Long, Integer> COST_TO_STAGE = buildCostTable();

    private static Map<Long, Integer> buildCostTable() {
        long[] cost = {
                5_000, 10_000, 15_000, 20_000, 30_000, 60_000,          // 1~6강
                100_000, 150_000, 210_000, 280_000, 360_000, 450_000,   // 7~12강
                550_000, 660_000, 780_000, 910_000, 1_050_000, 1_200_000, // 13~18강
                1_360_000, 1_530_000, 1_750_000, 2_000_000, 2_300_000, 2_600_000, // 19~24강
                2_950_000, 3_300_000, 3_700_000, 4_100_000, 4_550_000, 5_000_000  // 25~30강
        };
        Map<Long, Integer> m = new HashMap<>();
        for (int i = 0; i < cost.length; i++) m.put(cost[i], i + 1);
        return m;
    }

    /** 골드값이 바다의 가호 비용표에 있으면 단계(1~30), 없으면 0. */
    public static int stageOfCost(long gold) {
        Integer s = COST_TO_STAGE.get(gold);
        return s == null ? 0 : s;
    }

    /** lore 한 줄에서 "소모 재화 : N골드"의 N을 추출. 없으면 0. */
    public static long parseCostGold(String loreLine) {
        if (loreLine == null) return 0;
        Matcher m = COST_LINE.matcher(loreLine);
        return m.find() ? GoldFormatSafe.parse(m.group(1)) : 0;
    }

    /** "[경기] 입질 시간 감소율 (+5)" → "입질 시간 감소율". */
    public static String abilityLabel(String rawName) {
        if (rawName == null) return "";
        String s = rawName.replaceAll("§.", "").trim();
        s = s.replaceFirst("^\\[[^\\]]*\\]\\s*", "");        // [경기]/[결과] 태그
        s = s.replaceFirst("\\s*\\(\\s*[+-]?\\d+\\s*\\)\\s*$", ""); // (+5) 현재 단계
        return s.trim();
    }

    /** 콤마 제거 파서(공통 GoldFormat 의존을 피해 내부 구현). */
    private static final class GoldFormatSafe {
        static long parse(String s) {
            try {
                return Long.parseLong(s.replace(",", "").trim());
            } catch (Exception e) {
                return 0;
            }
        }
    }

    private static final class Seen {
        final String label;
        final long ts;
        Seen(String label, long ts) { this.label = label; this.ts = ts; }
    }

    private final Consumer<TransactionRecord> sink;
    private final Map<Long, Seen> recentCosts = new HashMap<>();
    private volatile long lastGuiTs = 0;

    public SeaBlessingTracker(Consumer<TransactionRecord> sink) {
        this.sink = sink;
    }

    /**
     * 로더 화면 감시기가 매 틱 호출.
     * @param costs 열린 바다의 가호 강화창에서 읽은 (소모 골드 → 능력치명).
     *              이 창이 아니거나 읽을 게 없으면 null/빈 맵.
     */
    public synchronized void updateGui(Map<Long, String> costs) {
        if (costs == null || costs.isEmpty()) return;
        long now = System.currentTimeMillis();
        lastGuiTs = now;
        for (Map.Entry<Long, String> e : costs.entrySet()) {
            recentCosts.put(e.getKey(), new Seen(e.getValue(), now));
        }
        recentCosts.entrySet().removeIf(en -> now - en.getValue().ts > COST_MEMORY_MS);
    }

    /** 강화창이 (유예 포함) 활성인가. */
    public boolean isActive(long now) {
        return now - lastGuiTs <= GRACE_MS;
    }

    /**
     * ΔG 를 바다의 가호 지출로 소비 시도.
     * @return true=기록·소비함(호출측은 Resolver로 넘기지 말 것). false=해당 없음.
     */
    public synchronized boolean tryConsume(long delta) {
        long now = System.currentTimeMillis();
        if (!isActive(now)) return false;   // 강화창 안 열림
        if (delta >= 0) return false;       // 골드 감소만

        long cost = -delta;
        Seen seen = recentCosts.get(cost);
        // 창에서 읽은 비용이 우선. 못 읽었으면 위키 비용표로 보조 판정(창은 이미 확인됨).
        if (seen == null && stageOfCost(cost) == 0) return false;

        String ability = seen == null ? "" : seen.label;
        String label = ability.isEmpty() ? "바다의 가호 강화" : "바다의 가호 " + ability;
        sink.accept(new TransactionRecord(now, TransactionRecord.Kind.EXPENSE, cost,
                "바다의 가호", label, 0,
                true, TransactionRecord.Confidence.HIGH, true, null));
        return true;
    }
}
