package kr.ddingtycoon.dtledger.core;

import java.util.regex.Pattern;

/**
 * 채팅 파싱 결과(원자 신호). CurrencyParser 가 생성, TransactionResolver 가 소비.
 * 금액은 본문에 있으므로 항상 신뢰(HIGH). 잔고 ΔG 는 교차검증용.
 */
public final class TradeSignal {

    // 서버 리소스팩 전용 장식 아이콘 글리프(트로피·보석 등) 제거용.
    // 제어문자 · 사설영역(BMP+확장A/B) · 동봉영숫자(①②…) · 딩벳(❗✅…) · 기타기호(★ 성급표시만 제외).
    // ★=U+2605 만 SaleCategory 해양 판정에 실제로 쓰임 — ☆(U+2606)은 보존 불필요, 장식으로 간주해 제거.
    // 이 음절/부수(U+A000~A4CF): 띵타이쿤이 등급 아이콘으로 쓰는 ꀁ~ꀈ 등. 한국어 품목명에는
    //   나올 일이 없어 전역 제거해도 안전(2026-08-07 확인: "ꀂ 키론 오르골" 형태로 붙어 나옴).
    private static final Pattern DECOR_ICONS = Pattern.compile(
            "[\\u0000-\\u001F\\u007F"
          + "\\uE000-\\uF8FF\\uFFF0-\\uFFFF"
          + "\\x{F0000}-\\x{FFFFD}\\x{100000}-\\x{10FFFD}"
          + "\\uA000-\\uA4CF"
          + "\\u2460-\\u24FF"
          + "\\u2700-\\u27BF"
          + "\\u2600-\\u2604\\u2606-\\u26FF"
          + "]");

    /**
     * 등급 아이콘으로 쓰이는 한자 글리프(算·山·産) — 품목명 앞뒤에 단독 토큰으로 붙는다.
     * 예: "해구의 파동 코어 山", "영생의 아쿠티스 産".
     *
     * <p>이들은 정상 한자라 통째로 지우면 진짜 한자가 든 이름을 훼손할 수 있어,
     * <b>공백으로 분리된 단독 글자</b>일 때만 제거한다. 앞쪽 글리프는 아래 clean() 의
     * 선두 정리로도 걸리지만, 꼬리에 붙은 건 이 규칙이 없으면 그대로 남아
     * 엑셀·HUD 에 한자로 노출됐다(2026-08-07 제보 확인).
     */
    private static final Pattern GRADE_GLYPH = Pattern.compile("(?:^|\\s)[算山産](?=\\s|$)");

    public enum Type {
        // 확정 (코딩계획서 §6)
        SALE,                 // 판매(수입) — NPC/무역 등 일반 판매
        USERSHOP_SALE,        // 유저상점 판매(수입) — 미매칭 시 "유저상점" 카테고리로 귀속
        USERSHOP_BUY,         // 유저상점 구매(지출)
        NPC_SHOP_BUY,         // 마을 상점(NPC, 예: 두리) 구매(지출) — 유저상점과 구분(2026-07-27 제보)
        FLEA_BUY,             // 플리마켓에서 구매(지출) — 금고 아님!
        BANK_DEPOSIT,         // 은행 입금(이체) + 선택적 수수료(지출)
        BANK_WITHDRAW,        // 은행 출금(이체) [추정 — 내일 확정]
        FLEA_VAULT_DEPOSIT,   // 플리마켓 금고 입금(이체)
        FLEA_VAULT_WITHDRAW,  // 플리마켓 금고 출금(이체)

        // 확장 슬롯 (내일 원문 확보 후 활성화 — 전체계획서 §4-A / 코딩계획서 §7)
        FLEA_SALE,            // 내 플리마켓 물건이 팔림(수입) — 금고로 유입, ΔG 없음
        FLEA_DIRECT_SALE,     // 플리마켓에 직접 판매(수입) — 메시지에 순수령액·수수료가 함께 옴
        FLEA_ORDER_FILLED,    // 내 매수주문 체결(지출) — 금고에서 유출, ΔG 없음
        USER_TRANSFER_IN,     // 직거래 수령(수입)
        USER_TRANSFER_OUT,    // 직거래 송금(지출)
        FISH_SYNTH,           // 낚시대회(파라다이스) 어획물 합성 보상(수입)
        CONTEST_PRIZE,        // 낚시대회 순위 보상(수입) — 우편으로 지급, 순위로 금액 확정
        AQUARIUM_RELEASE,     // 수족관 반려어 방생 대금(수입) — 채팅에 금액이 그대로 온다

        // 금액이 본문에 없어 ΔG 로 금액을 매기는 유형 (라벨만 채팅에서)
        SKILL_UPGRADE,        // 전문가 스킬 업그레이드(지출) — 골드+어빌리티스톤 소모, 메시지에 금액 없음
        WEAPON_ENHANCE,       // 무기/도구 강화(지출) — 골드+재료 소모. 성공/실패 모두 소모(장비·낚싯대…)
        ENGRAVE,              // 장비 각인(지출) — 골드+재료 소모. 성공/실패 모두 소모
        TOOL_REPAIR,          // 도구 내구도 수리(지출) — 골드+경험치 소모, 메시지에 금액 없음
        QUALITY_RESTORE,      // 도구 품질 회복(지출) — 골드+코어 소모. 내구도 수리와 비용이 달라 별도 유형
        ENGRAVE_INVESTIGATE,  // 수상한 각인석 조사(지출) — 성공·실패 무관 고정 30,000골드
        VILLAGE_INVEST,       // 마을 투자(지출) — 회수 불가라 이체가 아닌 순수 지출
        QUEST_REWARD,         // 일일/주간 의뢰 완료 보상(수입) — "의뢰를 완료하였습니다"만 뜨고 금액 없음
        MERMAID_RESET         // 인어의 축복 재설정(지출) — 사용자 확정: 1회당 고정 15,000골드
    }

    public final Type type;
    public final long amount;   // 본문 총액(SALE=판매총액, BUY=구매총액, DEPOSIT=입금액 …). amountFromDelta면 0.
    public final long fee;      // 수수료(은행 입금에서만 >0)
    public final int qty;       // 수량(없으면 0)
    public final String label;  // 품목/설명
    public final String raw;    // 원문(디버그/미분류 대비)
    public final boolean amountFromDelta; // true면 금액을 메시지가 아닌 ΔG 에서 가져옴

    public TradeSignal(Type type, long amount, long fee, int qty, String label, String raw) {
        this(type, amount, fee, qty, label, raw, false);
    }

    public TradeSignal(Type type, long amount, long fee, int qty, String label, String raw,
                       boolean amountFromDelta) {
        this.type = type;
        this.amount = amount;
        this.fee = fee;
        this.qty = qty;
        this.label = clean(label);
        this.raw = raw;
        this.amountFromDelta = amountFromDelta;
    }

    /**
     * 라벨에서 색코드(§x)·제어문자·서버 리소스팩 장식 아이콘 글리프를 제거하고 공백 정리.
     * 위 필터가 못 잡는 미지의 아이콘 대비, 마지막으로 "한글/영문/숫자/★☆가 아닌 선두 문자"를
     * 통째로 걷어내는 안전장치를 둔다(장식 아이콘은 대개 이름 맨 앞에 뭉쳐 붙어 나오므로).
     */
    private static String clean(String label) {
        if (label == null) return "";
        String s = label.replaceAll("§.", "");
        s = DECOR_ICONS.matcher(s).replaceAll("");
        s = GRADE_GLYPH.matcher(s).replaceAll("");
        s = s.replaceFirst("^[^\\p{IsHangul}A-Za-z0-9★]+", "");
        return s.trim().replaceAll("\\s+", " ");
    }

    /** 잔고가 늘어야 하면 +1, 줄어야 하면 -1, 알 수 없으면 0. */
    public int expectedSign() {
        return switch (type) {
            case SALE, USERSHOP_SALE, BANK_WITHDRAW, FLEA_VAULT_WITHDRAW, USER_TRANSFER_IN, FISH_SYNTH,
                 QUEST_REWARD, FLEA_DIRECT_SALE, CONTEST_PRIZE, AQUARIUM_RELEASE -> +1;
            case USERSHOP_BUY, NPC_SHOP_BUY, FLEA_BUY, BANK_DEPOSIT, FLEA_VAULT_DEPOSIT, USER_TRANSFER_OUT,
                 SKILL_UPGRADE, WEAPON_ENHANCE, ENGRAVE, TOOL_REPAIR, QUALITY_RESTORE,
                 ENGRAVE_INVESTIGATE, VILLAGE_INVEST,
                 MERMAID_RESET -> -1;
            // 금고 내부 이동 — 내 잔고(ΔG) 변화 없음
            case FLEA_SALE, FLEA_ORDER_FILLED -> 0;
        };
    }

    /**
     * ΔG 로 교차검증할 기대 금액의 절대값. 수수료가 잔고에서 따로 빠지면(은행입금·송금) 합산.
     * 단 플리마켓 직접판매는 수수료가 판매대금에서 선차감돼 잔고엔 순수령액만 들어오므로 제외.
     */
    public long expectedMagnitude() {
        return type == Type.FLEA_DIRECT_SALE ? amount : amount + fee;
    }

    /** 이 신호가 잔고 변동을 동반하지 않는가(금고 전용). */
    public boolean isVaultInternal() {
        return type == Type.FLEA_SALE || type == Type.FLEA_ORDER_FILLED;
    }

    /** 채팅 중복 표시 [N] 반영 — 금액·수수료·수량 N배. amountFromDelta 는 배수 대상 아님. */
    public TradeSignal times(int n) {
        if (n <= 1 || amountFromDelta) return this;
        long q = (long) qty * n;
        return new TradeSignal(type, amount * n, fee * n,
                (int) Math.min(Integer.MAX_VALUE, q), label + " ×" + n, raw);
    }

    @Override
    public String toString() {
        return "TradeSignal{" + type + " amount=" + amount + (fee > 0 ? " fee=" + fee : "")
                + (qty > 0 ? " qty=" + qty : "") + " label='" + label + "'}";
    }
}
