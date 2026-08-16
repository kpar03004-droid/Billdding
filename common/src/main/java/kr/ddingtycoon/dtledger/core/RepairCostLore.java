package kr.ddingtycoon.dtledger.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 도구 수리 창의 비용 표기를 읽는다(2026-07-30 실측).
 *
 *   [ 도구 수리하기 ]      - 수리 소모 골드 : 5,286골드
 *   [ 도구 품질 회복하기 ]  - 회복 비용 : 10000골드
 *
 * 두 비용 모두 채팅에는 안 나오고 실행할 때마다 값이 다르다:
 *   · 수리   — 인챈트(특수 인챈트·인챈트 정도)에 따라 다름. 같은 도구면 일정.
 *   · 품질회복 — 그 도구를 회복한 횟수마다 10,000골드씩 오름(도구마다 횟수가 다름).
 *
 * 그래서 규칙으로 역산할 수 없고, **창에 적힌 금액을 그대로 읽는 것**이 유일하게 정확하다.
 * 읽어둔 금액은 여러 번 연속 실행해 잔고 변동이 합쳐졌을 때 정확히 쪼개는 데 쓰인다.
 */
public final class RepairCostLore {
    private RepairCostLore() {}

    private static final Pattern REPAIR_COST =
            Pattern.compile("수리\\s*소모\\s*골드\\s*[:：]\\s*([\\d,]+)\\s*골드");
    private static final Pattern RESTORE_COST =
            Pattern.compile("회복\\s*비용\\s*[:：]\\s*([\\d,]+)\\s*골드");
    /**
     * 장비 강화 비용 — 2026-08-14 실측: "- 강화 비용 : 700,000골드, 10루비".
     * 골드와 루비가 한 줄에 같이 오므로 <b>골드 부분만</b> 잡는다(루비는 골드가 아님).
     * 재료를 다 채워야 이 줄이 나타난다(부족하면 "[강화 재료]" 목록만 뜸).
     */
    private static final Pattern ENHANCE_COST =
            Pattern.compile("강화\\s*비용\\s*[:：]\\s*([\\d,]+)\\s*골드");

    /**
     * lore 한 줄이 수리 비용이면 금액, 아니면 null.
     *
     * <p><b>0 과 null 을 반드시 구분한다</b> — "수리 소모 골드 : 0골드"(무료 수리)와
     * "그런 줄이 없음"은 완전히 다른 상황이다. 예전엔 둘 다 0 을 돌려줘서, 무료 수리인데도
     * 창을 못 읽은 것으로 취급해 <b>직전 수리 비용을 재사용</b>했다(2026-08-08 제보:
     * 0원 수리에 이전 3,638원이 찍힘).
     */
    public static Long parseRepairCost(String loreLine) {
        return first(REPAIR_COST, loreLine);
    }

    /** lore 한 줄이 품질 회복 비용이면 금액, 아니면 null(0 은 "무료"라는 뜻). */
    public static Long parseRestoreCost(String loreLine) {
        return first(RESTORE_COST, loreLine);
    }

    /** lore 한 줄이 장비 강화 비용이면 골드 금액, 아니면 null. 같은 줄의 루비는 무시. */
    public static Long parseEnhanceCost(String loreLine) {
        return first(ENHANCE_COST, loreLine);
    }

    private static Long first(Pattern p, String line) {
        if (line == null) return null;
        Matcher m = p.matcher(line);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1).replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }
}
