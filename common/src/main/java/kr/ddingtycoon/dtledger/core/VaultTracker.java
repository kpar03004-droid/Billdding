package kr.ddingtycoon.dtledger.core;

import java.util.function.LongConsumer;

/**
 * 플리마켓 금고 잔액 추적 (전체계획서 §4-A B안).
 *
 * 최초 1회 UI 에서 잔액을 입력받고, 이후 확정 레코드로 자동 갱신:
 *   +플리 판매 대금(CAT_FLEA_SALE) / -매수 체결 대금(CAT_FLEA_ORDER)
 *   +잔고→금고 입금(TRANSFER_OUT) / -금고→잔고 출금(TRANSFER_IN)
 *
 * "1회만 입력"의 드리프트 위험(이벤트 누락 시 영구 오차)은 금고 탭의
 * 재동기화로 해소. 계산이 음수/한도 초과가 되면 warning 을 세워 UI 에 표시.
 * MC 의존성 0 — 오프라인 단위테스트 가능.
 */
public final class VaultTracker {
    public static final long DEFAULT_LIMIT = 20_000_000L;

    private final long limit;
    private final LongConsumer persist; // 잔액 변경 시 저장 콜백
    private long balance;               // -1 = 미설정
    private String warning;             // null = 정상

    public VaultTracker(long initialBalance, long limit, LongConsumer persist) {
        this.limit = limit > 0 ? limit : DEFAULT_LIMIT;
        this.balance = initialBalance < 0 ? -1 : Math.min(initialBalance, this.limit);
        this.persist = persist;
    }

    public boolean isSet() { return balance >= 0; }
    public long balance() { return Math.max(balance, 0); }
    public long limit() { return limit; }
    public double fillRatio() { return isSet() ? (double) balance() / limit : 0; }
    public String warning() { return warning; }

    /** 최초 입력·재동기화(수동). 0~한도로 클램프, 경고 해제. */
    public void set(long v) {
        balance = Math.max(0, Math.min(v, limit));
        warning = null;
        persist.accept(balance);
    }

    /**
     * 서버 실측 잔액("/플리마켓 금고" 결과)으로 재동기화하고 미집계 차액을 돌려준다.
     *
     * 잠수 중에는 다른 서버로 옮겨져 플리마켓 판매 채팅이 아예 오지 않아(2026-07-28 확인)
     * 실시간 집계가 불가능하다. 대신 금고 잔액이 실측 근거이므로, 추적값과의 차액을
     * "그동안 놓친 금고 유입/유출"로 보고 호출측이 보정 레코드를 남길 수 있게 한다.
     *
     * @return 차액(+면 미집계 판매, -면 미집계 매수 체결). 최초 설정 시엔 0(기준선일 뿐이라 보정 안 함).
     */
    public long syncFromServer(long actual) {
        boolean wasSet = isSet();
        long prev = balance();
        set(actual);
        return wasSet ? balance() - prev : 0;
    }

    /** 금고 재동기화 차액에 대한 보정 레코드. 금고 잔액에 다시 반영되지 않는 카테고리를 쓴다. */
    public static TransactionRecord missedRecord(long diff, long ts) {
        boolean income = diff > 0;
        return new TransactionRecord(ts,
                income ? TransactionRecord.Kind.INCOME : TransactionRecord.Kind.EXPENSE,
                Math.abs(diff),
                "플리마켓", // CAT_FLEA_SALE/ORDER 를 쓰면 onRecord 가 금고에 또 반영해 이중계상됨
                income ? "잠수 중 판매(금고 보정)" : "잠수 중 매수 체결(금고 보정)",
                0, true, TransactionRecord.Confidence.MEDIUM, false,
                "금고 실측 잔액과의 차액 자동 보정");
    }

    /** 확정 레코드 반영. 금고와 무관한 레코드는 무시. */
    public void onRecord(TransactionRecord r) {
        if (!isSet()) return;
        long delta = deltaOf(r);
        if (delta == 0) return;
        long next = balance + delta;
        if (next < 0) {
            warning = "계산상 잔액이 음수 — 재동기화 필요";
            next = 0;
        } else if (next > limit) {
            warning = "계산상 한도 초과 — 재동기화 필요";
            next = limit;
        }
        balance = next;
        persist.accept(balance);
    }

    /** 레코드가 금고 잔액에 주는 변화량(+유입/-유출). */
    static long deltaOf(TransactionRecord r) {
        if (TransactionRecord.CAT_FLEA_SALE.equals(r.category)) return r.amount;
        if (TransactionRecord.CAT_FLEA_ORDER.equals(r.category)) return -r.amount;
        if (TransactionRecord.CAT_FLEA_VAULT.equals(r.category)) {
            // TRANSFER_OUT = 잔고→금고 입금(금고 +), TRANSFER_IN = 금고→잔고 출금(금고 -)
            return r.kind == TransactionRecord.Kind.TRANSFER_OUT ? r.amount : -r.amount;
        }
        return 0;
    }
}
