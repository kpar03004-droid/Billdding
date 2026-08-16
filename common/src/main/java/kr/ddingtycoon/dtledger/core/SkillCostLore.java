package kr.ddingtycoon.dtledger.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 전문가 스킬 창의 업그레이드 비용을 읽는다(2026-08-13 실측).
 *
 * <pre>
 *   [세레니티] 재배학개론 (+3)
 *     ! 전문가 스킬 정보
 *     - 보유 스킬 포인트 : 748포인트
 *     - 소모 스킬 포인트 : 10포인트
 *     - 소모 재화 : 100,000골드      ← 이 값
 *     - 소모 아이템 : 어빌리티 스톤 20개
 * </pre>
 *
 * <p><b>왜 필요한가</b> — 업그레이드 채팅에는 금액이 없어서 지금까지 잔고 변동(ΔG)으로
 * 금액을 매겼는데, 창을 열어둔 동안 골드 표시가 갱신되지 않아 15초 대기창을 넘기면
 * 지출이 통째로 누락됐다(2026-08-13 제보). 수리·의뢰·바다의 가호가 이미 같은 문제를
 * 창 읽기로 해결했고, 전문가 스킬만 남아 있었다.
 *
 * <p>"소모 재화 : N골드"는 바다의 가호 창과 문구가 같으므로,
 * <b>"소모 스킬 포인트"가 함께 있을 때만</b> 전문가 스킬로 인정한다.
 */
public final class SkillCostLore {
    private SkillCostLore() {
    }

    /** 이 줄이 있어야 전문가 스킬 창으로 인정 — 바다의 가호와 구분하는 유일한 근거. */
    public static final String SIGNATURE = "소모 스킬 포인트";

    private static final Pattern COST = Pattern.compile("소모\\s*재화\\s*[:：]\\s*([\\d,]+)\\s*골드");

    /** lore 한 줄이 업그레이드 비용이면 금액, 아니면 null(0 은 "무료"라는 뜻). */
    public static Long parseCost(String loreLine) {
        if (loreLine == null) return null;
        Matcher m = COST.matcher(loreLine);
        if (!m.find()) return null;
        try {
            return Long.parseLong(m.group(1).replace(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isSignature(String loreLine) {
        return loreLine != null && loreLine.contains(SIGNATURE);
    }

    /**
     * 아이템 표시명에서 스킬 이름만 뽑는다.
     * "[세레니티] 재배학개론 (+3)" → "재배학개론"
     *
     * <p>채팅은 "재배학개론 스킬 업그레이드에 성공했습니다"로 오므로, 이 이름으로
     * 어떤 스킬을 올렸는지 짝지을 수 있다. 창에 스킬이 여러 개 떠 있어도 정확하다.
     */
    public static String skillName(String rawName) {
        if (rawName == null) return "";
        String s = rawName.replaceAll("§.", "");
        s = s.replaceFirst("^\\s*\\[[^\\]]*\\]\\s*", "");   // 앞의 [세레니티] 등 지역 태그
        s = s.replaceFirst("\\s*\\([^)]*\\)\\s*$", "");      // 뒤의 (+3) 강화 수치
        s = s.replaceFirst("^[^\\p{IsHangul}A-Za-z0-9]+", ""); // 남은 장식 글리프
        return s.trim().replaceAll("\\s+", " ");
    }

    /**
     * 채팅 라벨("재배학개론 스킬", "재배학개론 스킬 실패")이 이 스킬 이름의 것인가.
     * 라벨은 파서가 "{이름} 스킬" 꼴로 만든다.
     */
    public static boolean matchesLabel(String skillName, String label) {
        if (skillName == null || skillName.isBlank() || label == null) return false;
        return label.startsWith(skillName);
    }
}
