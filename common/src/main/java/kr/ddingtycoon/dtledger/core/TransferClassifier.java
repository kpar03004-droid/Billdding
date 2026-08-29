package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Confidence;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Kind;
import kr.ddingtycoon.dtledger.util.GoldFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * TradeSignal → 1개 이상의 TransactionRecord.
 * 회계 규칙의 단일 집결지(CLAUDE.md "회계 규칙").
 *
 * 핵심:
 *  - 금고/은행 입출금 = TRANSFER(손익 제외).
 *  - 은행 입금은 TRANSFER_OUT(입금액) + EXPENSE(수수료) 두 레코드로 분리.
 *  - 플리마켓 '구매'는 EXPENSE(금고 아님).
 *  - 이중계상 금지: 금고 유입(FLEA_SALE)은 INCOME, 금고→잔고 출금은 TRANSFER.
 */
public final class TransferClassifier {

    /** ΔG 교차검증 결과. */
    public enum CrossCheck { MATCHED_EXACT, MATCHED_SIGN, NONE }

    private final DtConfig config;

    public TransferClassifier(DtConfig config) {
        this.config = config;
    }

    public List<TransactionRecord> classify(TradeSignal sig, CrossCheck cc, long ts) {
        List<TransactionRecord> out = new ArrayList<>(2);

        Confidence conf = switch (cc) {
            case MATCHED_EXACT -> Confidence.HIGH;
            case MATCHED_SIGN, NONE -> Confidence.MEDIUM;
        };
        boolean crossed = cc != CrossCheck.NONE;
        String note = switch (cc) {
            case MATCHED_SIGN -> "금액 불일치(판매세/수수료 의심)";
            case NONE -> sig.isVaultInternal() ? "금고 내부(ΔG 없음이 정상)" : "ΔG 미검출(메시지 금액 사용)";
            case MATCHED_EXACT -> null;
        };

        switch (sig.type) {
            // NPC·무역 판매는 활동(전문가)별로 — 판매처가 곧 그 콘텐츠라 활동과 일치한다.
            case SALE ->
                out.add(income(ts, sig.amount, SaleCategory.of(sig.label, sig.raw), sig.label, sig.qty, conf, crossed, note));
            // 유저상점 판매는 품목과 무관하게 "유저상점" — 사용자 투표 결과 B안 채택(2026-07-28).
            // 판매처가 정확히 남는 쪽을 택했고, 무엇을 팔았는지는 라벨로 확인한다.
            case USERSHOP_SALE ->
                out.add(income(ts, sig.amount, "유저상점", sig.label, sig.qty, conf, crossed, note));
            // 마을 상점(NPC) 구매 — 유저상점과 별개 카테고리(2026-07-27 제보: 두리에게 산 램프가
            // "유저상점"으로 표기됨). 문구도 다름: NPC="구입하셨습니다", 유저상점="구매하였습니다".
            case NPC_SHOP_BUY ->
                out.add(expense(ts, sig.amount, "상점", sig.label, sig.qty, conf, crossed, note));
            case USERSHOP_BUY ->
                out.add(expense(ts, sig.amount, "유저상점", sig.label, sig.qty, conf, crossed, note));
            case FLEA_BUY ->
                // 사용자 확정(2026-07-23): "즉시구매" 구분 불필요 → "플리마켓"으로 표기 통일.
                // 금고(CAT_FLEA_ORDER)와는 별개 문자열 — 매수주문 체결과 혼동 방지, 금고 추적 로직 무관(FLEA_BUY는 금고 아님).
                out.add(expense(ts, sig.amount, "플리마켓", "플리마켓", sig.qty, conf, crossed, note));

            case BANK_DEPOSIT -> {
                out.add(transfer(ts, Kind.TRANSFER_OUT, sig.amount, "은행", "은행 입금", conf, crossed, note));
                if (sig.fee > 0) {
                    out.add(feeRecord(ts, sig.fee, "은행 수수료", conf, crossed));
                }
            }
            case BANK_WITHDRAW ->
                out.add(transfer(ts, Kind.TRANSFER_IN, sig.amount, "은행", "은행 출금", conf, crossed, note));
            case FLEA_VAULT_DEPOSIT ->
                out.add(transfer(ts, Kind.TRANSFER_OUT, sig.amount, TransactionRecord.CAT_FLEA_VAULT, "금고 입금", conf, crossed, note));
            case FLEA_VAULT_WITHDRAW ->
                out.add(transfer(ts, Kind.TRANSFER_IN, sig.amount, TransactionRecord.CAT_FLEA_VAULT, "금고 출금", conf, crossed, note));

            // 플리마켓 직접 판매 — 메시지가 순수령액을 그대로 주므로 추가 수수료 계산 없음.
            // 수수료는 선차감분이라 별도 지출 레코드를 만들지 않고 note 에만 남김(이중 차감 방지).
            // 카테고리 "플리마켓" — 금고 추적(CAT_FLEA_SALE)과 분리: 이 대금은 잔고로 들어옴.
            case FLEA_DIRECT_SALE -> {
                String feeNote = sig.fee > 0
                        ? "수수료 " + GoldFormat.format(sig.fee) + " 차감(총액 "
                          + GoldFormat.format(sig.amount + sig.fee) + ")"
                        : note;
                out.add(income(ts, sig.amount, "플리마켓", sig.label, sig.qty, conf, crossed, feeNote));
            }
            case FLEA_SALE -> {
                // 판매 대금은 수수료가 선차감된 금액만 금고로 들어옴(2026-07-27 제보: 수수료 미반영).
                // 유저상점 판매(rule 61)와 동일하게 순수령액만 INCOME — 별도 수수료 지출 레코드를
                // 만들면 손익에서 이중 차감되고, 금고 잔액도 총액 기준으로 부풀려짐.
                long fee = Math.round(sig.amount * config.fleaSaleFeePercent / 100.0);
                long net = Math.max(0, sig.amount - fee);
                String feeNote = fee > 0
                        ? "수수료 " + GoldFormat.format(fee) + " 차감(총액 " + GoldFormat.format(sig.amount) + ")"
                        : note;
                out.add(income(ts, net, TransactionRecord.CAT_FLEA_SALE, sig.label, sig.qty, conf, crossed, feeNote));
            }
            case FLEA_ORDER_FILLED ->
                out.add(expense(ts, sig.amount, TransactionRecord.CAT_FLEA_ORDER, sig.label, sig.qty, conf, crossed, note));
            case FISH_SYNTH, CONTEST_PRIZE ->
                out.add(income(ts, sig.amount, "낚시대회", sig.label, sig.qty, conf, crossed, note));
            // 수족관은 낚시대회와 별개 콘텐츠(어항 모듈에서 키운 반려어) — 카테고리를 분리한다.
            case AQUARIUM_RELEASE ->
                out.add(income(ts, sig.amount, "수족관", sig.label, sig.qty, conf, crossed, note));
            case USER_TRANSFER_IN ->
                out.add(income(ts, sig.amount, "송금", sig.label, 0, conf, crossed, note));
            case MERMAID_RESET ->
                out.add(expense(ts, sig.amount, "인어의 축복", sig.label, 0, conf, crossed, note));
            // 각인석 조사는 각인 콘텐츠의 재료 확보 과정 → 장비 각인과 같은 "각인" 카테고리로 묶는다
            // (아이콘도 수상한 각인석이라 그대로 맞음). 성공·실패는 라벨로 구분.
            case ENGRAVE_INVESTIGATE ->
                out.add(expense(ts, sig.amount, "각인", sig.label, 0, conf, crossed, note));
            // 마을 투자 — 회수 불가(창 안내문)이므로 은행·금고 같은 TRANSFER 가 아니라 EXPENSE.
            case VILLAGE_INVEST ->
                out.add(expense(ts, sig.amount, "마을 투자", sig.label, 0, conf, crossed, note));
            case USER_TRANSFER_OUT -> {
                out.add(expense(ts, sig.amount, "송금", sig.label, 0, conf, crossed, note));
                if (sig.fee > 0) {
                    out.add(feeRecord(ts, sig.fee, "송금 수수료", conf, crossed));
                }
            }
        }
        return out;
    }

    // ── helpers ──
    private TransactionRecord income(long ts, long amt, String cat, String label, int qty,
                                     Confidence c, boolean crossed, String note) {
        return new TransactionRecord(ts, Kind.INCOME, amt, cat, label, qty, true, c, crossed, note);
    }

    private TransactionRecord expense(long ts, long amt, String cat, String label, int qty,
                                      Confidence c, boolean crossed, String note) {
        return new TransactionRecord(ts, Kind.EXPENSE, amt, cat, label, qty, true, c, crossed, note);
    }

    private TransactionRecord feeRecord(long ts, long amt, String label, Confidence c, boolean crossed) {
        return new TransactionRecord(ts, Kind.EXPENSE, amt, "수수료", label, 0,
                config.feeCountedAsExpense, c, crossed, null);
    }

    private TransactionRecord transfer(long ts, Kind kind, long amt, String cat, String label,
                                       Confidence c, boolean crossed, String note) {
        boolean counted = !config.transferExcludedFromPnl;
        return new TransactionRecord(ts, kind, amt, cat, label, 0, counted, c, crossed, note);
    }
}
