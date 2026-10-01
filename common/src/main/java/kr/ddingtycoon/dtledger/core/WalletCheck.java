package kr.ddingtycoon.dtledger.core;

import java.time.LocalDate;

/**
 * 잔고 대조 — "이번 접속 동안 실제 잔고가 움직인 만큼 기록도 움직였나".
 *
 * <p>(지금 잔고 − 기준 잔고) − (기록의 잔고 영향 합 − 기준 시점 합) = 기록에 없는 변동.
 * 0 이 아니면 기록이 빠졌거나(누락) 틀렸다(금액 오류). 제보 전에 사용자가 먼저 알아챈다.
 *
 * <p>ΔG 를 하나씩 더하지 않고 <b>잔고 절대값</b>을 비교한다 — 월드 이동 중 정착 구간이나
 * 중복 ΔG 무시로 빠진 변동도 절대값에는 남으므로 결국 맞아떨어진다.
 * 거래 처리 중엔 잠깐 어긋나므로 {@link #STABLE_MS} 동안 값이 그대로일 때만 확정한다.
 *
 * <p>기준은 접속(또는 날짜 바뀜) 후 처음 읽은 잔고. 접속 사이의 변동·다른 서버는 섞지 않는다.
 * MC 의존성 0.
 */
public final class WalletCheck {
    /** 화면·틱 루프가 함께 보는 실행 중 인스턴스. */
    public static final WalletCheck LIVE = new WalletCheck();

    static final long STABLE_MS = 10_000;

    private boolean started;
    private LocalDate day;
    private long startBalance;
    private long startEffect;
    private long lastBalance;
    private long lastEffect;

    private long candidate;
    private long candidateSince;
    private Long confirmed; // null = 아직 확정 전

    /** 레코드가 <b>지갑(잔고)</b>에 준 변화. 금고 안에서만 오간 돈은 0. */
    public static long walletDelta(TransactionRecord r) {
        if (r == null || r.kind == null) return 0;
        if (TransactionRecord.CAT_FLEA_SALE.equals(r.category)
                || TransactionRecord.CAT_FLEA_ORDER.equals(r.category)
                || VaultTracker.NOTE_VAULT_SYNC.equals(r.note)) return 0;
        // 손익 제외(수수료 설정·이체)라도 잔고에서는 실제로 나갔다 — countedInPnl 과 무관
        return switch (r.kind) {
            case INCOME, TRANSFER_IN -> r.amount;
            case EXPENSE, TRANSFER_OUT -> -r.amount;
        };
    }

    /**
     * 매 틱 호출. balance 가 null(못 읽음·월드 이동 직후)이면 상태를 그대로 둔다.
     *
     * @param effect 오늘 기록의 잔고 영향 합
     * @param idle   대기 중인 거래(신호·ΔG)가 없는가
     */
    public synchronized void observe(long now, Long balance, LocalDate today, long effect, boolean idle) {
        if (balance == null || today == null) return;
        if (!started || !today.equals(day)) {
            started = true;
            day = today;
            rebaseTo(balance, effect, now);
            return;
        }
        lastBalance = balance;
        lastEffect = effect;
        long diff = (balance - startBalance) - (effect - startEffect);
        if (!idle || diff != candidate) {
            candidate = diff;
            candidateSince = now;
        }
        if (idle && now - candidateSince >= STABLE_MS) confirmed = candidate;
    }

    /** 지금 차이를 "알고 넘어감" — 기준을 현재로 옮긴다. */
    public synchronized void acknowledge(long now) {
        if (started) rebaseTo(lastBalance, lastEffect, now);
    }

    /** 재접속 — 다음에 읽는 잔고부터 새 기준. */
    public synchronized void reset() {
        started = false;
        confirmed = null;
    }

    /** 한 번이라도 잔고를 읽었는가. false 면 이 서버·설정에서는 대조 불가. */
    public synchronized boolean started() { return started; }

    /** 확정된 "기록에 없는 변동"(+면 기록보다 잔고가 더 늘었음). 확정 전이면 null. */
    public synchronized Long unexplained() { return confirmed; }

    private void rebaseTo(long balance, long effect, long now) {
        startBalance = lastBalance = balance;
        startEffect = lastEffect = effect;
        candidate = 0;
        candidateSince = now;
        confirmed = null;
    }
}
