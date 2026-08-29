package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.util.GoldFormat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 채팅 원문 → TradeSignal. 코딩계획서 §6 확정 정규식.
 *
 * List<Rule> 구조 — 내일 확보분(직거래 송금·직업별 판매·플리마켓 판매)은
 * addRule(...) 한 줄 추가만으로 편입(§7 확장 포인트). 다른 코드 불변.
 *
 * 우선순위 숫자가 작을수록 먼저 검사. 처음 매칭으로 확정.
 */
public final class CurrencyParser {

    @FunctionalInterface
    public interface Mapper {
        TradeSignal map(Matcher m, String raw);
    }

    public record Rule(int priority, Pattern pattern, Mapper mapper) {}

    private final List<Rule> rules = new ArrayList<>();

    public void addRule(int priority, String regex, Mapper mapper) {
        rules.add(new Rule(priority, Pattern.compile(regex), mapper));
        rules.sort(Comparator.comparingInt(Rule::priority));
    }

    /** 무시할 메시지(골드와 무관). 매칭 시 파싱 중단(null 반환) — 이후 규칙 오작동 방지. */
    public void addIgnore(int priority, String regex) {
        rules.add(new Rule(priority, Pattern.compile(regex), (m, raw) -> null));
        rules.sort(Comparator.comparingInt(Rule::priority));
    }

    // 채팅 중복 겹침 표시: "…판매받았습니다. (판매자: X) [2]" → N배로 집계
    private static final Pattern REPEAT = Pattern.compile("\\s*\\[(\\d{1,4})\\]\\s*$");

    /** 수상한 각인석 조사 비용 — 성공·실패 무관 고정(2026-07-30 확인). 서버가 바꾸면 여기만 수정. */
    private static final long ENGRAVE_INVESTIGATE_COST = 30_000;

    // 낚시대회 순위 보상표(위키): 1위 500,000G, 등수당 10,000G 감소, 30위까지 지급.
    private static final long CONTEST_PRIZE_TOP = 500_000;
    private static final long CONTEST_PRIZE_STEP = 10_000;
    private static final int CONTEST_PRIZE_LAST_RANK = 30;

    // "/플리마켓 금고" 결과 스냅샷: "플리마켓 금고 잔액: 12,635,710 골드"
    //   거래가 아니라 실제 잔액 통보 → 금고 재동기화(set)용. 앞 아이콘/색코드 허용(find).
    private static final Pattern VAULT_BALANCE =
            Pattern.compile("플리마켓 금고 잔액\\s*:\\s*([\\d,]+)\\s*골드");

    /**
     * "/플리마켓 금고" 결과 메시지면 실제 금고 잔액을 반환(재동기화용), 아니면 -1.
     * 레코드를 만들지 않는다 — 스냅샷이므로 VaultTracker.set() 으로 직접 반영.
     */
    public static long parseVaultBalance(String raw) {
        if (raw == null) return -1;
        Matcher m = VAULT_BALANCE.matcher(raw);
        return m.find() ? GoldFormat.parse(m.group(1)) : -1;
    }

    /** 매칭 없으면 null (→ resolver 가 ΔG 로 catch-all 처리). */
    public TradeSignal parse(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String msg = raw.trim();

        // 겹친 메시지 [N] 분리 → 승수
        int repeat = 1;
        Matcher rm = REPEAT.matcher(msg);
        if (rm.find()) {
            try {
                repeat = Math.max(1, Integer.parseInt(rm.group(1)));
            } catch (NumberFormatException ignored) {
                repeat = 1;
            }
            msg = msg.substring(0, rm.start()).trim();
        }

        for (Rule r : rules) {
            Matcher m = r.pattern().matcher(msg);
            if (m.find()) {
                TradeSignal s = r.mapper().map(m, msg);
                return (s == null || repeat <= 1) ? s : s.times(repeat);
            }
        }
        return null;
    }

    private static long g(Matcher m, int i) {
        return GoldFormat.parse(m.group(i));
    }

    private static int gi(Matcher m, int i) {
        return (int) Math.min(Integer.MAX_VALUE, GoldFormat.parse(m.group(i)));
    }

    /** 코딩계획서 §6 순서대로 확정 규칙을 등록한 기본 파서. */
    public static CurrencyParser createDefault() {
        CurrencyParser p = new CurrencyParser();

        // 0) 무시 — 플리마켓 슬롯 재고 빼내기. "재고를 N만큼 빼냈습니다"는 아이템 수량(골드 아님!).
        //    (사용자 확인: 물건을 뺄 때 뜨는 메시지 — 골드 거래로 혼동 금지)
        p.addIgnore(1, "^플리마켓 슬롯 재고를 ([\\d,]+)개?\\s*만큼 빼냈습니다");

        // 0-2) 무시 — 타 플레이어 거래 메시지(계획서 §3: 주어 있으면 배제).
        //    "{닉}이/가|님이 …판매/구매/입금/출금/송금" 형태만 걸러 내 거래 오분류를 방지.
        //    내 메시지는 [태그]·품목명·"마을 은행에"·"플리마켓…"으로 시작하므로 안 걸림.
        p.addIgnore(2, "^\\S+?(?:님이|[이가])\\s.*(?:판매|구매|입금|출금|송금)");

        // 0-3) 무시 — 위 규칙의 구멍 보강(2026-07-27 제보: 마을 은행 공용 방송이 내 거래로 잡힘).
        //    실제 방송은 앞에 아이콘/색코드/[태그]가 붙어 "^\S+?님이" 앵커를 비껴가므로,
        //    선두 비한글(아이콘·색코드)과 [태그] 를 건너뛴 뒤의 "{닉}님이/님께서/님은" 을 잡는다.
        //    마을 은행은 마을원 공용이라 남의 입출금 방송이 그대로 내 잔고 거래로 오계상됐음.
        //    내 메시지에는 "님이/님께서/님은" 이 없고(직거래는 "님에게"라 안 걸림) 안전.
        p.addIgnore(2, "^[^가-힣]*(?:\\[[^\\]]*\\]\\s*)*\\S+?님(?:이|께서|은)\\s");

        // ── 직거래/송금 (2026-07-20 실측 확정) ──────────────────────────
        // 거래창 요약("거래 내역" 블록)과 직접 송금 메시지가 같은 거래를 중복 보고할 수
        // 있으므로, TransactionResolver 가 USER_TRANSFER_* 를 시간창 내 중복 제거함.

        // 3) 거래창: "받은 골드 : 100골드" → USER_TRANSFER_IN (트리문자 등 비한글 접두 허용)
        p.addRule(3, "^[^가-힣]*받은 골드\\s*:\\s*([\\d,]+)골드",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USER_TRANSFER_IN,
                        g(m, 1), 0, 0, "거래 수령", raw));

        // 4) 거래창: "보낸 골드 : 10,000골드 (수수료 500골드)" → USER_TRANSFER_OUT (+수수료)
        p.addRule(4, "^[^가-힣]*보낸 골드\\s*:\\s*([\\d,]+)골드(?:\\s*\\(수수료:?\\s*([\\d,]+)\\s*골드\\))?",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USER_TRANSFER_OUT,
                        g(m, 1), m.group(2) != null ? g(m, 2) : 0, 0, "거래 지급", raw));

        // 5) 직접 송금 보냄: "{닉}님에게 10,000골드를 보냈습니다. (수수료: 500 골드)"  (앵커 없음: 앞 아이콘 허용)
        p.addRule(5, "(\\S+?)님에게 ([\\d,]+)골드를 보냈습니다\\.(?:\\s*\\(수수료:?\\s*([\\d,]+)\\s*골드\\))?",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USER_TRANSFER_OUT,
                        g(m, 2), m.group(3) != null ? g(m, 3) : 0, 0, m.group(1) + "님에게 송금", raw));

        // 6) 직접 송금 받음: "{닉}님에게 10,000골드를 받았습니다." (님에게서 변형 포함)
        p.addRule(6, "(\\S+?)님에게(?:서)? ([\\d,]+)골드를 받았습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USER_TRANSFER_IN,
                        g(m, 2), 0, 0, m.group(1) + "님에게서 수령", raw));

        // ※ 아래 내용 규칙은 앵커(^) 없음 — 메시지 앞 아이콘/색코드 문자에 강함.
        //    타 플레이어 방송(주어 있음)은 위 ignore(우선순위 2)가 먼저 걸러냄.

        // 8) 전문가 스킬 업그레이드(지출) — 메시지에 금액 없음 → 금액은 ΔG, 라벨만 채팅에서.
        //    "해양학개론 스킬 업그레이드에 성공했습니다." (골드+어빌리티스톤 소모, 골드분만 집계)
        //    2026-07-28: 지출 미집계 제보 — 어미/결과 표기 변형에 대비해 넓힘.
        //    강화·각인처럼 '했/하였'이 혼용되고 실패 문구도 있을 수 있어 둘 다 허용하고,
        //    마침표(!/. 등)도 요구하지 않는다. RPG '스킬 강화'(rule 9)와는 문구가 달라 충돌 없음.
        //    ※ 근본 원인이던 "ΔG 1.5초 내 미도착 시 지출 폐기"는 Resolver 의 연장 대기창으로 해결됨.
        //    라벨은 기존 표기("○○ 스킬") 유지 — 실패했을 때만 뒤에 표시해 내역에서 구분.
        p.addRule(8, "(.+?)\\s*스킬 업그레이드에\\s*(성공|실패)(?:했|하였)습니다",
                (m, raw) -> {
                    boolean failed = "실패".equals(m.group(2));
                    return new TradeSignal(TradeSignal.Type.SKILL_UPGRADE,
                            0, 0, 0, m.group(1) + " 스킬" + (failed ? " 실패" : ""), raw, true);
                });

        // 9) 무기/도구 강화·장비 각인(지출) — 세이지 도구·RPG 무기·선샤인 낚싯대 등.
        //    골드+재료 소모, 성공/실패 모두 소모(사용자 확인 2026-07-21). 메시지에 금액 없음 →
        //    금액은 ΔG, 라벨만 채팅에서(SKILL_UPGRADE 와 동일 구조).
        //    실측 문구: "낚싯대 강화에 성공했습니다!" / "장비 강화에 성공했습니다." /
        //             "장비 강화에 실패했습니다." / "장비 각인에 실패하였습니다." /
        //             "스킬 강화에 실패했습니다."(RPG 스킬 강화) ← 무기 이름 대신 일반 명칭 사용.
        //             어미도 '했/하였' 혼용됨. 성공/실패 모두 골드+재료 소모 → 둘 다 지출.
        //    g1=대상(장비/낚싯대/곡괭이/괭이/대검/무기/스킬), g2=강화|각인, g3=성공|실패.
        //    전문가 스킬(rule 8 '스킬 업그레이드')·인챈트와 문구가 달라 충돌 없음("스킬 강화"≠"스킬 업그레이드").
        p.addRule(9, "(장비|무기|낚싯대|곡괭이|괭이|대검|스킬)\\s*(강화|각인)에\\s*(성공|실패)(?:했|하였)습니다",
                (m, raw) -> {
                    boolean engrave = m.group(2).equals("각인");
                    return new TradeSignal(
                            engrave ? TradeSignal.Type.ENGRAVE : TradeSignal.Type.WEAPON_ENHANCE,
                            0, 0, 0, m.group(1) + " " + m.group(2) + " " + m.group(3), raw, true);
                });

        // 9c) 도구 내구도 수리(지출) — 2026-07-30 실측: "장비 내구도가 모두 수리되었습니다."
        //     수리 창에 "수리 소모 골드 : 5,286골드"가 뜨지만 채팅엔 금액이 없고, 손상도에 따라
        //     매번 달라진다 → 강화·각인과 동일하게 ΔG 로 금액을 매긴다.
        //     부분 수리 문구 변형("모두" 없음)도 함께 허용.
        p.addRule(9, "내구도가\\s*(?:모두\\s*)?수리되었습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.TOOL_REPAIR,
                        0, 0, 0, "도구 수리", raw, true));

        // 9d) 도구 품질 회복(지출) — 2026-07-30 실측:
        //     "장비 품질이 +65 회복되어 205로 향상되었습니다."  (창: "회복 비용 : 10000골드")
        //     메시지의 숫자는 품질 수치이지 골드가 아니므로 금액은 ΔG 로 잡는다.
        //     내구도 수리(9c)와 비용 체계가 달라 유형을 분리(합산 분할 시 섞이지 않게).
        p.addRule(9, "장비 품질이\\s*[+\\-]?[\\d,]+\\s*회복되어",
                (m, raw) -> new TradeSignal(TradeSignal.Type.QUALITY_RESTORE,
                        0, 0, 0, "품질 회복", raw, true));

        // 9b) 각인 '성공' 문구는 실패와 구조가 완전히 다름 — 2026-07-29 실측(제보: 성공만 미집계):
        //     성공 "아이템에 각인을 성공적으로 적용했습니다!"  vs  실패 "장비 각인에 실패하였습니다."
        //     rule 9 는 "각인에 성공/실패"만 잡아 성공을 놓쳤다. 성공도 골드가 소모되므로 지출.
        //     라벨은 실패와 짝이 맞게 "장비 각인 성공"으로 통일(내역에서 나란히 보이도록).
        p.addRule(9, "각인을\\s*성공적으로\\s*적용했습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.ENGRAVE,
                        0, 0, 0, "장비 각인 성공", raw, true));

        // 11) 일일/주간 의뢰 완료 보상(수입, 사용자 확인: 완료 시 골드 항상 지급) — 메시지에 금액
        //     없음 → 금액은 ΔG(SKILL_UPGRADE와 동일 구조). 2026-07-23 실측: "❕ 의뢰를 완료하였습니다"
        //     (아이콘/색코드만 앞에 붙음). 어미 했/하였 혼용(강화·각인과 동일 패턴) → 둘 다 허용,
        //     마침표도 "!" 등으로 다를 수 있어 종결 문자 요구 안 함.
        //     타 플레이어 방송("{닉}님이 의뢰를...")은 "님이" 등 한글이 앞에 와서 ^[^가-힣]* 앵커에 안 걸림.
        // 🚫 폐기(2026-07-28) — 절대 되살리지 말 것. 이 방식은 "부호만 맞는 아무 +ΔG"를 잡아
        //     같은 순간의 판매·송금 대금을 통째로 의뢰 보상으로 오기록했다(실측 +93,285,292).
        //     수입은 경쟁 신호가 너무 많아 ΔG 추측이 성립하지 않는다.
        //     → 대체 구현: QuestRewardTracker — 의뢰 창 lore("보상 : 20,000골드")에서 정확한
        //       금액을 읽어 그 값과 일치하는 +ΔG 만 인정한다. 아래 규칙은 기록용으로만 남긴다.
        // p.addRule(11, "^[^가-힣]*의뢰(?:를|을)?\\s*완료(?:했|하였)습니다",
        //         (m, raw) -> new TradeSignal(TradeSignal.Type.QUEST_REWARD,
        //                 0, 0, 0, "의뢰 완료", raw, true));

        // 12) 인어의 축복 재설정(지출) — 사용자 확정(2026-07-23): 1회당 고정 15,000골드.
        //     "인어의 축복을 재설정했습니다! {보너스 종류}"(보너스 종류는 매번 다름 → 라벨엔 안 씀).
        //     금액이 고정값으로 알려져 있어 ΔG 불필요(amountFromDelta 아님) — 메시지만으로 HIGH 확정,
        //     ΔG 는 교차검증용으로만 사용(SKILL_UPGRADE류와 달리 ΔG 미검출이어도 기록 안 사라짐).
        p.addRule(12, "^[^가-힣]*인어의 축복을 재설정했습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.MERMAID_RESET,
                        15_000, 0, 0, "인어의 축복 재설정", raw));

        // 13) 수상한 각인석 조사(지출) — 2026-07-30 실측:
        //     성공 "수상한 각인석 조사에 성공했습니다!"
        //     실패 "수상한 각인석 조사에 실패하여 조각을 얻었습니다."
        //     창에 "조사 비용 : 30,000골드"로 표시되며 성공·실패 무관하게 매번 고정 3만원 소모
        //     (사용자 확인). 금액이 확정값이라 ΔG 를 기다리지 않고 메시지만으로 기록한다.
        //     "각인석"은 rule 9 의 "(강화|각인)에" 패턴과 겹치지 않아 충돌 없음.
        p.addRule(13, "수상한 각인석 조사에\\s*(성공|실패)",
                (m, raw) -> new TradeSignal(TradeSignal.Type.ENGRAVE_INVESTIGATE,
                        ENGRAVE_INVESTIGATE_COST, 0, 0, "각인석 조사 " + m.group(1), raw));

        // 14) 마을 투자(지출) — 2026-08-02 실측: "50,000골드를 마을에 투자하였습니다."
        //     확인 대화상자("...골드를 투자하시겠습니까? 완료 후에는 투자한 골드를 회수할 수 없습니다.")가
        //     먼저 뜨는데 그건 아직 돈이 안 나간 상태다 → "마을에 투자하였습니다"(완료문)만 잡는다.
        //     "회수할 수 없습니다" = 돌려받지 못함 → 은행·금고 같은 이체가 아니라 순수 지출.
        p.addRule(14, "([\\d,]+)\\s*골드를\\s*마을에\\s*투자하였습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.VILLAGE_INVEST,
                        g(m, 1), 0, 0, "마을 투자", raw));

        // 15) 수족관 반려어 방생(수입) — 2026-08-29 실측:
        //     "반려 물고기를 방생하여 234,000골드를 받았습니다."
        //     어항 창의 "방생 대금"과 같은 금액이 채팅에 그대로 오므로 창을 읽을 필요가 없다.
        //     쉬프트클릭 전체 방생도 마리당 한 줄씩 오는지 미확인 — 합계 한 줄이면 그대로 1건이 된다.
        //     기존 "받았습니다" 규칙들(송금 6·플리 46/47)은 앞에 닉네임/플리마켓이 붙어 충돌하지 않는다.
        p.addRule(15, "반려\\s*물고기를\\s*방생하여\\s*([\\d,]+)\\s*골드를\\s*받았습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.AQUARIUM_RELEASE,
                        g(m, 1), 0, 0, "반려 물고기 방생", raw));

        // 1) 은행 입금 (+선택적 수수료) → TRANSFER_OUT(입금액) + EXPENSE/FEE(수수료)
        p.addRule(10, "마을 은행에 ([\\d,]+)\\s*골드를 입금했습니다\\.(?:\\s*\\(수수료:\\s*([\\d,]+)\\s*골드\\))?",
                (m, raw) -> new TradeSignal(TradeSignal.Type.BANK_DEPOSIT,
                        g(m, 1), m.group(2) != null ? g(m, 2) : 0, 0, "은행 입금", raw));

        // 2) 은행 출금 → TRANSFER_IN  (2026-07-20 실측 확정: "마을 은행에서 N 골드를 출금했습니다.")
        p.addRule(20, "마을 은행에서 ([\\d,]+)\\s*골드를 출금했습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.BANK_WITHDRAW,
                        g(m, 1), 0, 0, "은행 출금", raw));

        // 3) 플리마켓 금고 입금 → TRANSFER_OUT
        p.addRule(30, "플리마켓 금고에 ([\\d,]+)\\s*골드를 입금했습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_VAULT_DEPOSIT,
                        g(m, 1), 0, 0, "금고 입금", raw));

        // 4) 플리마켓 금고 출금 → TRANSFER_IN
        p.addRule(40, "플리마켓 금고에서 ([\\d,]+)\\s*골드를 출금했습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_VAULT_WITHDRAW,
                        g(m, 1), 0, 0, "금고 출금", raw));

        // 45) 내 플리마켓 판매 성사(금고 유입=수입, ΔG 없음) — 2026-07-20 실측 확정
        //     "[플리마켓] 플리마켓에서 유리 99개가 3,000골드에 구매되었습니다. (구매자: {닉})"
        p.addRule(45, "플리마켓에서 (.+?)\\s*([\\d,]+)개가 ([\\d,]+)골드에 구매되었습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_SALE,
                        g(m, 3), 0, gi(m, 2), m.group(1), raw));

        // 46) 내 매수주문 체결(금고 유출=지출, ΔG 없음) — 2026-07-20 실측 확정
        //     "[플리마켓] 플리마켓에서 유리 99개를 3,000골드에 판매받았습니다. (판매자: {닉})"
        p.addRule(46, "플리마켓에서 (.+?)\\s*([\\d,]+)개를 ([\\d,]+)골드에 판매받았습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_ORDER_FILLED,
                        g(m, 3), 0, gi(m, 2), m.group(1), raw));

        // 47) 플리마켓에 직접 판매(수입) — 2026-07-27 실측(제보 #4: 수익 미집계):
        //     "플리마켓에 64개를 판매하여 45,410골드(수수료: 2,390골드)를 받았습니다. [2]"
        //     rule 45/46("플리마켓에서 …")과 조사가 달라(에 vs 에서) 충돌 없음.
        //     ※ 이 문구는 순수령액을 그대로 알려줌(45,410+2,390=47,800, 수수료 정확히 5%)
        //       → 메시지 금액을 그대로 INCOME. 수수료는 선차감분이라 별도 지출 레코드 없음
        //       (만들면 손익 이중 차감). g1=수량, g2=순수령액, g3=수수료.
        p.addRule(47, "플리마켓에\\s*([\\d,]+)개를\\s*판매하여\\s*([\\d,]+)골드\\s*(?:\\(수수료:\\s*([\\d,]+)\\s*골드\\))?를?\\s*받았습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_DIRECT_SALE,
                        g(m, 2), m.group(3) != null ? g(m, 3) : 0, gi(m, 1), "플리마켓 판매", raw));

        // 5) 플리마켓 구매(지출) → EXPENSE. g1=수량, g2=총액
        p.addRule(50, "플리마켓에서 ([\\d,]+)개를 ([\\d,]+)골드에 구매했습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FLEA_BUY,
                        g(m, 2), 0, gi(m, 1), "플리마켓 구매", raw));

        // 6) 유저상점 구매(지출) → EXPENSE. g2=품목, g3=총액
        p.addRule(60, "(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s*아이템을\\s*([\\d,]+)골드에 구매하였습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USERSHOP_BUY,
                        g(m, 3), 0, 0, m.group(2), raw));

        // 59) 마을 상점(NPC) 구매(지출) → EXPENSE. 2026-07-23 실측 — rule 60과 문구 다름
        //     (구입하셨습니다/수량 있음 vs 구매하였습니다/수량 없음):
        //     "[ROOKIE] 상자 잠금 자물쇠 아이템 64개를 64,000골드에 구입하셨습니다."
        //     g1=등급, g2=품목, g3=수량, g4=총액
        p.addRule(59, "(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s*아이템\\s*([\\d,]+)개를\\s*([\\d,]+)골드에\\s*구입하셨습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.NPC_SHOP_BUY,
                        g(m, 4), 0, gi(m, 3), m.group(2), raw));

        // 61) 유저상점 판매(수입, 수수료 선차감) → INCOME. 2026-07-21 실측:
        //     "[RARE] 중급 라이프스톤 아이템 판매 금액 수수료 5,850골드를 제외한 111,150골드를 수령하였습니다."
        //     수수료는 잔고에 애초에 안 들어오는 금액(판매자 부담, 물품가에서 선차감)이라
        //     별도 EXPENSE 레코드 만들지 않음 — 순수령액만 INCOME(ΔG와 정확히 일치).
        //     g1=등급, g2=품목, g3=수수료(미사용), g4=순수령액
        //     USERSHOP_SALE 타입 — 전문가 미매칭 시 "유저상점" 카테고리로 귀속(SaleCategory).
        p.addRule(61, "(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s*아이템 판매 금액 수수료\\s*([\\d,]+)골드를 제외한\\s*([\\d,]+)골드를 수령하였습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.USERSHOP_SALE,
                        g(m, 4), 0, 0, m.group(2), raw));

        // 62) 판매(수입) '…아이템을 N골드에 판매하였습니다' 형식 — 무역/유저상점 판매. 2026-07-20 실측:
        //     "무역 아이템을 45,780골드에 판매하였습니다."  g2=품목, g3=총액
        p.addRule(62, "(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s*아이템을\\s*([\\d,]+)골드에 판매하였습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 3), 0, 0, m.group(2), raw));

        // 63) 바리스타(커피) 판매 → INCOME. 2026-07-21 실측:
        //     "커피 판매를 완료했습니다. 222,084 골드를 얻었습니다."
        //     개별 커피명(라떼/모카/…) 없이 "커피" 고정 문구만 나옴 — label="커피"로도
        //     SaleCategory.BARISTA 키워드 매칭돼 "바리스타"로 잡힘. g1=총액
        p.addRule(63, "커피 판매를 완료했습니다\\.\\s*([\\d,]+)\\s*골드를 얻었습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 1), 0, 0, "커피", raw));

        // 63b) 바리스타(커피) 일부 판매 → INCOME. 2026-07-21 실측:
        //     "커피를 일부 판매했습니다. 169,884 골드를 얻었습니다."
        //     완료(63)와 별개 문구 — 재고 일부만 팔렸을 때 나오는 것으로 추정. g1=총액
        p.addRule(63, "커피를 일부 판매했습니다\\.\\s*([\\d,]+)\\s*골드를 얻었습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 1), 0, 0, "커피", raw));

        // 63c) 품목명 없는 판매 완료(수렵꾼 육식동물 위탁판매) → INCOME. 2026-07-27 실측(제보 #8):
        //     "판매를 완료했습니다. 20,145 골드를 얻었습니다."  ← 커피(63)와 달리 앞에 품목명이 없음.
        //     반드시 63(커피)보다 뒤 우선순위여야 커피가 이 규칙에 먼저 먹히지 않음.
        //     ⚠ 문구에 품목 정보가 전혀 없어 무엇을 팔았는지 알 수 없음 — 제보가 육식동물 판매였고
        //       커피는 전용 문구가 따로 있으므로 육식동물(사냥)로 귀속. 다른 콘텐츠에서도 같은
        //       문구가 뜬다면 그 콘텐츠까지 사냥으로 잡히므로 라벨을 보고 조정 필요. g1=총액
        p.addRule(66, "판매를 완료했습니다\\.\\s*([\\d,]+)\\s*골드를 얻었습니다",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 1), 0, 0, "육식동물 판매", raw));

        // 64) 낚시대회(파라다이스) 어획물 합성 보상 → INCOME. 2026-07-21 실측:
        //     "물고기를 합성했습니다. 하급 파도 결정석 6개, 73,224G (포인트 12,204)"
        //     g1=획득품, g2=수량, g3=골드(G접미사). 포인트는 무시.
        p.addRule(64, "합성했습니다\\.\\s*(.+?)\\s*([\\d,]+)개,\\s*([\\d,]+)G",
                (m, raw) -> new TradeSignal(TradeSignal.Type.FISH_SYNTH,
                        g(m, 3), 0, gi(m, 2), m.group(1), raw));

        // 64b) 낚시대회 순위 보상(수입) — 2026-07-28 실측(제보 #6b). 우편으로 지급되고 수령 시
        //      별도 채팅이 없어, 도착 알림의 "N위"로 금액을 확정한다(위키 보상표: 1위 500,000G,
        //      한 등수당 10,000G 감소 → 30위 210,000G. 검산: 28위=230,000 ✓).
        //      "타이니랜드 낚시 대회 26일 2회차 1위 보상 우편이 도착했습니다. [/우편함]에서 …"
        //      ※ 우편 수령 시점의 +ΔG 는 매칭 신호가 없어 폐기되므로 이중계상 없음.
        //      ※ 31위 이하는 골드 보상이 없어 기록하지 않음. 아쿠아 코인은 골드가 아니라 제외.
        p.addRule(64, "대회.*?(\\d+)위\\s*보상 우편이 도착했습니다",
                (m, raw) -> {
                    int rank = Integer.parseInt(m.group(1));
                    long gold = CONTEST_PRIZE_TOP - (long) (rank - 1) * CONTEST_PRIZE_STEP;
                    if (rank < 1 || rank > CONTEST_PRIZE_LAST_RANK || gold <= 0) return null;
                    return new TradeSignal(TradeSignal.Type.CONTEST_PRIZE,
                            gold, 0, 0, "낚시대회 " + rank + "위 보상", raw);
                });

        // 65) NPC 판매(귀중품·기타) → INCOME. 2026-07-20 실측:
        //     "귀중품을 판매하여 286,028골드를 획득했습니다."
        //     로니/샤키/픽스 등 "…판매하여 N골드를 획득했습니다" 형식 통합. g1=품목, g2=총액
        p.addRule(65, "^(.+?)(?:을|를) 판매하여 ([\\d,]+)골드를 획득했습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 2), 0, 0, m.group(1), raw));

        // 7) 판매(수입) → INCOME. g2=품목, g3=수량, g4=총액
        //    등급 태그 다양(RARE/ROOKIE/…) → [^\]]+ 로 확장. 품목 예: "코룸 정동석", "평범한 표범"
        p.addRule(70, "(?:\\[([^\\]]+)\\]\\s*)?(.+?)\\s*아이템\\s*([\\d,]+)개를\\s*([\\d,]+)골드에 판매하셨습니다\\.",
                (m, raw) -> new TradeSignal(TradeSignal.Type.SALE,
                        g(m, 4), 0, gi(m, 3), m.group(2), raw));

        // ─────────────────────────────────────────────────────────────
        // 남은 확장 슬롯: NPC별 판매 문구(샤키=물고기·픽스=공룡원혼·수렵꾼) 가
        //   "판매하셨습니다" 형식과 다르면 여기 규칙 행 추가. 미확보분은 catch-all 보존.
        //   (플리마켓 판매/매수체결·직거래 송금은 2026-07-20 실측으로 확정 편입 완료)
        // ─────────────────────────────────────────────────────────────

        return p;
    }
}
