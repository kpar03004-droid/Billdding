package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.util.GoldFormat;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 제보용 최근 활동 기록 — 인식한 채팅 · 잔고 변동 · 확정 기록을 시간순으로 모은다.
 *
 * <p>"금액이 이상해요" 제보는 스크린샷만으론 원인을 못 찾는다. 같은 순간의 채팅 원문,
 * 잔고 변동, 모드가 적은 기록을 한 덩어리로 받으면 바로 재현 테스트를 만들 수 있다.
 * 메모리에만 두고 파일·서버로 보내지 않는다. {@code /빌띵 제보}가 클립보드로만 꺼낸다.
 */
public final class ActivityLog {
    private ActivityLog() {}

    /** 버그 제보 구글 폼(로그인 불필요). */
    public static final String REPORT_FORM_URL = "https://forms.gle/zDsZQ7AYuaeqUAhq8";

    private static final int MAX = 80;
    /** 디스코드 메시지 한도(2,000자) 안에 코드블록까지 들어가게. */
    static final int REPORT_LIMIT = 1_900;
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    static synchronized void add(String kind, String text) {
        LINES.addLast(LocalTime.now().format(T) + " " + kind + " " + text);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static void signal(TradeSignal s) {
        if (s != null) add("채팅", s.type + " | " + s.raw);
    }

    /** 규칙에 안 걸린 채팅 중 돈 얘기만 — 새 패치 문구를 찾는 단서. */
    public static void unmatched(String raw) {
        if (raw != null && raw.contains("골드")) add("미인식", raw);
    }

    public static void delta(long d) {
        add("잔고", GoldFormat.signed(d));
    }

    public static void record(TransactionRecord r) {
        if (r == null) return;
        String sign = r.kind == TransactionRecord.Kind.INCOME || r.kind == TransactionRecord.Kind.TRANSFER_IN ? "+" : "-";
        add("기록", sign + GoldFormat.format(r.amount) + " " + r.category + " / " + r.label
                + (r.note == null ? "" : " (" + r.note + ")"));
    }

    /** 제보문 머리말 — 버전·로더·잔고 읽기 상태·잔고 대조·마지막 처리 결과. */
    public static List<String> header(String version, String loader, String balanceRead) {
        List<String> h = new ArrayList<>();
        h.add("빌띵 " + version + " · " + loader + " · MC 1.21.4");
        h.add("잔고 읽기: " + (balanceRead == null ? "한 번도 못 읽음" : strip(balanceRead)));
        Long gap = WalletCheck.LIVE.unexplained();
        h.add("잔고 대조: " + (!WalletCheck.LIVE.started() ? "불가" : gap == null ? "확인 중"
                : gap == 0 ? "일치" : "기록에 없는 변동 " + GoldFormat.signed(gap)));
        String settle = TransactionResolver.lastSettleInfo();
        if (settle != null) h.add("최근 처리: " + settle);
        return h;
    }

    /** 마인크래프트 색 코드(§x) 제거. */
    static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    /** 디스코드에 그대로 붙일 평문. 길면 오래된 줄부터 뺀다. */
    public static synchronized String report(List<String> header) {
        List<String> lines = new ArrayList<>(LINES);
        StringBuilder head = new StringBuilder("```\n");
        for (String h : header) head.append(h).append('\n');
        head.append("--- 최근 활동 (오래된 순) ---\n");
        String tail = "```\n※ 다른 사람 이름이 있으면 지우고 붙여 주세요.";
        int budget = REPORT_LIMIT - head.length() - tail.length();
        int from = lines.size();
        int used = 0;
        while (from > 0 && used + lines.get(from - 1).length() + 1 <= budget) {
            used += lines.get(from - 1).length() + 1;
            from--;
        }
        StringBuilder out = new StringBuilder(head);
        if (from == lines.size()) out.append("(기록된 활동 없음)\n");
        for (int i = from; i < lines.size(); i++) out.append(lines.get(i)).append('\n');
        return out.append(tail).toString();
    }

    static synchronized void clearForTest() {
        LINES.clear();
    }
}
