package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.TransferClassifier.CrossCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 시간창 W 안에서 ChatSignal ↔ 잔고 ΔG 를 결합해 최종 레코드를 만든다.
 *
 *  - 메시지에 금액이 있으면 그 금액을 사용(HIGH). ΔG 로 교차검증.
 *  - 금고 내부(FLEA_SALE 등)는 ΔG 없이 메시지만으로 확정.
 *  - 어디에도 안 걸린 ΔG → 기타(미분류).
 *
 * 매 클라 틱마다 tick(now) 로 만료분을 확정한다(스레드: 클라 메인 틱).
 */
public final class TransactionResolver {
    private static final Logger LOG = LoggerFactory.getLogger("dtledger");

    private static final long DELTA_DEDUP_MS = 500;  // 동일 ΔG 반복(잔고 소스 노이즈) 억제창

    /**
     * ΔG 로 금액을 매기는 신호(강화·각인·전문가 업그레이드)의 잔고변동 대기창.
     * 이들 거래는 GUI 안에서 일어나 잔고 표시 갱신이 메시지보다 한참 늦게 오는 경우가 많다.
     * 기본 시간창(1.5s)만 쓰면 "ΔG 미검출, 스킵"으로 지출이 통째로 누락됨(2026-07-27 제보:
     * 낚싯대 강화·각인 지출 안 잡힘) → 이 유형만 더 길게 기다린다.
     */
    private static final long DELTA_SIGNAL_WAIT_MS = 15_000; // 6s→15s (2026-07-28: 여전히 누락 제보)

    /**
     * 내 잔고가 실제로 움직였을 때만 인정할 유형.
     *
     * 마을 은행은 마을원 공용이라 **남의 입출금도 내 채팅에 뜬다**(2026-07-27 제보: 남의 입금이
     * 내 지출로, 남의 출금이 내 수익으로 잡힘). 메시지에 닉네임이 붙는지는 서버 구현에 달렸지만,
     * "내 잔고가 변했는가"는 서버 구현과 무관하게 확실하다 — 남이 입금하면 내 잔고는 그대로다.
     * 그래서 이 유형들은 ΔG 매칭이 없으면 기록하지 않는다(놓치더라도 남의 거래를 섞지 않는다).
     */
    private static final java.util.EnumSet<TradeSignal.Type> REQUIRE_DELTA_TYPES = java.util.EnumSet.of(
            TradeSignal.Type.BANK_DEPOSIT, TradeSignal.Type.BANK_WITHDRAW);

    /** 서버가 같은 거래를 2줄로 방송할 수 있어 동일 금액 중복을 막을 유형. */
    private static final java.util.EnumSet<TradeSignal.Type> DUP_GUARD_TYPES = java.util.EnumSet.of(
            TradeSignal.Type.BANK_DEPOSIT, TradeSignal.Type.BANK_WITHDRAW,
            TradeSignal.Type.FLEA_VAULT_DEPOSIT, TradeSignal.Type.FLEA_VAULT_WITHDRAW);

    private static final class PendingSignal {
        final TradeSignal sig;
        final long ts;
        CrossCheck matched;   // null=아직
        Long matchedDelta;    // 매칭된 ΔG 값(amountFromDelta 금액 산정용)
        PendingSignal(TradeSignal sig, long ts) { this.sig = sig; this.ts = ts; }
    }

    private static final class PendingDelta {
        final long delta;
        final long ts;
        PendingDelta(long delta, long ts) { this.delta = delta; this.ts = ts; }
    }

    private final DtConfig config;
    private final TransferClassifier classifier;
    private final Consumer<TransactionRecord> sink;

    private final List<PendingSignal> signals = new ArrayList<>();
    private final List<PendingDelta> deltas = new ArrayList<>();

    private long lastDeltaValue = 0;
    private long lastDeltaTs = 0;

    /** 창에서 읽어둔 유형별 비용 후보 (비용 → 마지막으로 본 시각). 합산 분해에 사용. */
    private final Map<TradeSignal.Type, Map<Long, Long>> guiCosts = new java.util.HashMap<>();
    private static final long GUI_COST_MEMORY_MS = 120_000;

    /**
     * 로더가 수리 창에서 읽은 비용을 알려준다(매 틱).
     * 이 값이 있으면 여러 번 연속 실행해 잔고 변동이 합쳐져도 정확히 쪼갤 수 있다.
     */
    /** 유형별로 "창에 0원이라고 적혀 있던" 마지막 시각. 무료 수리를 유료로 오기록하지 않기 위함. */
    private final Map<TradeSignal.Type, Long> zeroCostSeenAt = new java.util.HashMap<>();

    /** 전문가 스킬 이름 → (업그레이드 비용, 본 시각). 창에 여러 스킬이 떠 있어 이름으로 구분한다. */
    private final Map<String, long[]> skillCosts = new java.util.HashMap<>();

    /**
     * 로더 화면 감시기가 전문가 스킬 창에서 읽은 (스킬 이름 → 비용)을 알려준다(매 틱).
     *
     * <p>2026-08-13 제보: 창을 열어둔 채 시간이 지나면 골드 표시가 안 갱신돼 ΔG 가 안 오고,
     * 15초 대기창을 넘겨 지출이 통째로 누락됐다. 수리·의뢰·바다의 가호처럼 <b>창에 적힌 금액</b>을
     * 근거로 삼으면 ΔG 를 기다릴 필요가 없다.
     */
    public synchronized void noteSkillCosts(Map<String, Long> costs) {
        if (costs == null || costs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : costs.entrySet()) {
            if (e.getValue() == null || e.getValue() < 0) continue;
            skillCosts.put(e.getKey(), new long[]{e.getValue(), now});
        }
        skillCosts.entrySet().removeIf(en -> now - en.getValue()[1] > GUI_COST_MEMORY_MS);
        lastGuiCostInfo = "전문가 스킬 " + skillCosts.size() + "종 비용 확보";
    }

    /** 채팅 라벨("재배학개론 스킬")에 해당하는 창 표시 비용. 없으면 null. */
    private Long skillCostFor(String label) {
        long now = System.currentTimeMillis();
        String best = null;
        for (Map.Entry<String, long[]> e : skillCosts.entrySet()) {
            if (now - e.getValue()[1] > GUI_COST_MEMORY_MS) continue;
            if (!SkillCostLore.matchesLabel(e.getKey(), label)) continue;
            // 이름이 겹칠 때(예: "재배학" vs "재배학개론") 더 긴 쪽이 정확하다
            if (best == null || e.getKey().length() > best.length()) best = e.getKey();
        }
        return best == null ? null : skillCosts.get(best)[0];
    }

    public synchronized void noteGuiCost(TradeSignal.Type type, long cost) {
        long now = System.currentTimeMillis();
        if (cost == 0) {
            // 무료 수리(2026-08-08 제보: "수리 소모 골드 : 0골드"). 창을 못 읽은 것과는 다르다 —
            // 이때 직전 수리 비용을 재사용하면 없는 지출이 생긴다.
            zeroCostSeenAt.put(type, now);
            lastGuiCostInfo = type + " 0골드(무료)";
            return;
        }
        if (cost < 0) return;
        zeroCostSeenAt.remove(type);
        Map<Long, Long> m = guiCosts.computeIfAbsent(type, k -> new java.util.HashMap<>());
        m.put(cost, now);
        m.entrySet().removeIf(en -> now - en.getValue() > GUI_COST_MEMORY_MS);

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<TradeSignal.Type, Map<Long, Long>> e : guiCosts.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" / ");
            sb.append(e.getKey()).append(" ").append(e.getValue().keySet());
        }
        lastGuiCostInfo = sb.toString();
    }

    public TransactionResolver(DtConfig config, TransferClassifier classifier,
                               Consumer<TransactionRecord> sink) {
        this.config = config;
        this.classifier = classifier;
        this.sink = sink;
    }

    /** 진단용(/빌띵 진단) — 마지막으로 파싱된 채팅 신호. 채팅 규칙이 먹었는지 바로 확인. */
    private static volatile String lastSignalInfo;
    /** 진단용 — 창에서 읽어둔 비용 요약. */
    private static volatile String lastGuiCostInfo;

    /** 진단용 — 마지막 잔고 변동, 마지막 확정 결과. 왜 기록이 안 됐는지 바로 보인다. */
    private static volatile String lastDeltaInfo;
    private static volatile String lastSettleInfo;

    public static String lastSignalInfo() { return lastSignalInfo; }
    public static String lastGuiCostInfo() { return lastGuiCostInfo; }
    public static String lastDeltaInfo() { return lastDeltaInfo; }
    public static String lastSettleInfo() { return lastSettleInfo; }

    public void onSignal(TradeSignal sig) {
        if (sig == null) return;
        long now = System.currentTimeMillis();
        lastSignalInfo = sig.type + " · " + sig.label;

        // 은행/금고 입출금은 서버가 같은 거래를 2줄로 방송하는 경우가 있음(2026-07-27 제보:
        // "입금 시 채팅 2번 → 지출 2번 계상"). 같은 유형·같은 금액이 시간창 안에 또 오면 무시.
        // (수동 GUI 조작이라 1.5초 내 동일 금액 반복 입출금은 정상 시나리오가 아님)
        if (DUP_GUARD_TYPES.contains(sig.type)) {
            for (PendingSignal ps : signals) {
                if (ps.sig.type == sig.type && ps.sig.amount == sig.amount
                        && now - ps.ts <= config.matchWindowMs) {
                    LOG.info("[dtledger] 중복 은행/금고 신호 무시: {}", sig);
                    return;
                }
            }
        }

        // 송금은 거래창 요약("보낸/받은 골드")과 직접 메시지("님에게 …")가 같은 거래를
        // 중복 보고할 수 있음 → 같은 방향·금액이 시간창 안에 있으면 정보 많은 쪽만 유지.
        if (sig.type == TradeSignal.Type.USER_TRANSFER_IN || sig.type == TradeSignal.Type.USER_TRANSFER_OUT) {
            Iterator<PendingSignal> it = signals.iterator();
            while (it.hasNext()) {
                PendingSignal ps = it.next();
                if (ps.sig.type == sig.type && ps.sig.amount == sig.amount
                        && now - ps.ts <= config.matchWindowMs) {
                    if (sig.fee > ps.sig.fee) {
                        it.remove(); // 수수료 정보가 있는 신규가 우세 → 기존 교체
                        break;
                    }
                    LOG.info("[dtledger] 중복 송금 신호 무시: {}", sig);
                    return;
                }
            }
        }
        signals.add(new PendingSignal(sig, now));
    }

    public void onDelta(long delta) {
        if (delta == 0) return;
        long now = System.currentTimeMillis();
        // 동일 ΔG 가 아주 짧은 간격으로 반복되면(잔고 소스 노이즈/중복 읽기) 무시.
        // 단, 강화/각인처럼 같은 금액을 연속 소모하는 정상 반복 중(=ΔG금액형 신호 대기 중)이면
        // dedup 예외 — 연속 강화·각인 실패의 골드 소모를 과소집계하지 않도록.
        if (delta == lastDeltaValue && now - lastDeltaTs < DELTA_DEDUP_MS
                && !hasPendingDeltaSignal()) {
            LOG.info("[dtledger] 중복 ΔG 무시: {}", delta);
            return;
        }
        lastDeltaValue = delta;
        lastDeltaTs = now;
        lastDeltaInfo = (delta > 0 ? "+" : "") + delta;
        deltas.add(new PendingDelta(delta, now));
    }

    /** ΔG 를 기다리는 신호(금액 산정용 또는 내 거래 확인용)가 남아 있는가 — 델타 보관 연장 판단. */
    private boolean hasPendingDeltaSignal() {
        for (PendingSignal ps : signals) {
            if (ps.matched != null) continue;
            if (ps.sig.amountFromDelta || REQUIRE_DELTA_TYPES.contains(ps.sig.type)) return true;
        }
        return false;
    }

    /** 만료된 신호/델타를 확정. 클라 틱에서 매번 호출. */
    public void tick(long now) {
        long w = config.matchWindowMs;

        // 1) 도착한 델타를 즉시 정확 매칭 시도(부호+금액 일치). 금고 전용·ΔG금액형은 제외.
        for (PendingSignal ps : signals) {
            if (ps.matched != null || ps.sig.isVaultInternal() || ps.sig.amountFromDelta) continue;
            Long d = takeDelta(ps.sig.expectedSign(), ps.sig.expectedMagnitude(), true);
            if (d != null) {
                ps.matched = CrossCheck.MATCHED_EXACT;
                ps.matchedDelta = d;
            }
        }

        // 2-a) ΔG금액형(강화·각인·전문가)은 유형별로 묶어서 한 번에 처리.
        //   연속 시도하면 잔고가 제멋대로 합쳐 들어온다(실측: 강화 4회 700,000씩인데
        //   -700,000 / -1,400,000 / -700,000 세 번). 변동 하나씩 보면 건수가 안 맞고,
        //   개별 변동을 인원수로 나누면 우연히 나눠떨어질 때 엉뚱하게 쪼개진다(700,000÷4).
        //   → **전부 걷어 총액을 시도 횟수로 나누는** 방식이 안전하고 정확하다.
        settleDeltaSignals(now, w);

        // 2-b) 나머지 만료 신호 확정
        Iterator<PendingSignal> sit = signals.iterator();
        while (sit.hasNext()) {
            PendingSignal ps = sit.next();
            if (ps.sig.amountFromDelta) continue; // 2-a 가 유형별로 묶어 처리(먼저 지우면 안 됨)

            // 잔고 확인이 필요한 유형(마을 은행)은 ΔG 가 늦게 와도 놓치지 않게 더 기다린다.
            boolean needsDelta = REQUIRE_DELTA_TYPES.contains(ps.sig.type);
            long limit = needsDelta ? Math.max(w, DELTA_SIGNAL_WAIT_MS) : w;
            if (now - ps.ts <= limit) continue;

            // 내 잔고가 안 움직였으면 남의 거래(마을 은행 공용 방송) → 기록하지 않음
            if (needsDelta && ps.matched == null) {
                LOG.info("[dtledger] {} — 내 잔고 무변동, 타인 거래로 보고 스킵", ps.sig.label);
                sit.remove();
                continue;
            }

            CrossCheck cc = ps.matched != null ? ps.matched : CrossCheck.NONE; // 메시지 금액으로 확정
            for (TransactionRecord rec : classifier.classify(ps.sig, cc, ps.ts)) {
                emit(rec);
            }
            sit.remove();
        }

        // 3) 만료 델타(신호와 결합 안 됨)는 폐기. 기타(catch-all) 없앰 — 메시지 없는 잔고 변동은
        //    기록하지 않고 수동 입력(관리 탭)으로 처리. ΔG 는 교차검증·전문가 금액 산정에만 사용.
        //    단 ΔG금액형 신호가 대기 중이면 그 신호가 늦게 오는 잔고변동을 잡을 수 있게 함께 연장.
        long deltaKeep = hasPendingDeltaSignal() ? Math.max(w, DELTA_SIGNAL_WAIT_MS) : w;
        Iterator<PendingDelta> dit = deltas.iterator();
        while (dit.hasNext()) {
            PendingDelta pd = dit.next();
            if (now - pd.ts <= deltaKeep) continue;
            dit.remove();
        }
    }

    /**
     * 만료된 ΔG금액형 신호들을 유형별로 묶어 확정한다.
     *
     * 같은 유형의 연속 시도는 잔고가 임의로 합쳐 들어오므로(3회 시도인데 변동은 2번 등),
     * 변동을 하나씩 신호에 붙이면 건수가 어긋난다. 대신 부호가 맞는 변동을 **전부 걷어
     * 합계를 시도 횟수로 나눈다** — 같은 대상을 연속 시도하면 회당 비용이 같으므로 정확하다.
     * 나눠떨어지지 않으면(비용이 다른 시도가 섞임) 변동 하나씩 순서대로 배정한다.
     */
    private void settleDeltaSignals(long now, long w) {
        long limit = Math.max(w, DELTA_SIGNAL_WAIT_MS);
        Map<TradeSignal.Type, List<PendingSignal>> groups = new java.util.LinkedHashMap<>();
        for (PendingSignal ps : signals) {
            if (!ps.sig.amountFromDelta || now - ps.ts <= limit) continue;
            groups.computeIfAbsent(ps.sig.type, k -> new ArrayList<>()).add(ps);
        }
        if (groups.isEmpty()) return;

        for (Map.Entry<TradeSignal.Type, List<PendingSignal>> e : groups.entrySet()) {
            List<PendingSignal> group = e.getValue();
            int sign = group.get(0).sig.expectedSign();
            List<Long> taken = takeAllDeltas(sign);

            // 수리·품질회복은 ΔG 를 믿지 않는다 — 창에 적힌 금액만 근거로 삼는다.
            // 이 둘은 창을 열어야만 가능하고 창에 비용이 항상 적혀 있다(매 틱 읽고 있다).
            // 반면 ΔG 는 다른 거래가 겹치거나 잔고 표시를 한 틱 잘못 읽으면 통째로 틀리는데,
            // 실제로 0원 수리에 보유 골드 전액(1.7억)이 지출로 박힌 사고가 났다(2026-08-08 제보).
            if (VARIABLE_COST_TYPES.contains(e.getKey())) {
                settleByGuiCostOnly(e.getKey(), group, sign, taken);
                signals.removeAll(group);
                continue;
            }

            // 창 금액 우선 유형(강화) — 창에서 비용을 읽었으면 ΔG 가 뭐라 하든 그 금액을 쓴다.
            // 못 읽었으면 아래 기존 ΔG 경로로 그대로 내려간다.
            if (GUI_COST_PREFERRED_TYPES.contains(e.getKey())) {
                long[] byGui = recentCosts(e.getKey(), group.size());
                if (byGui != null) {
                    for (int i = 0; i < group.size(); i++) {
                        emitDeltaRecord(group.get(i), sign, byGui[i], "창 표시 금액");
                    }
                    takeAllDeltas(sign); // 잘못된 ΔG 가 다른 신호로 새지 않게 함께 소비
                    lastSettleInfo = e.getKey() + " " + group.size() + "건 — 창 표시 금액으로 기록";
                    signals.removeAll(group);
                    continue;
                }
            }

            // 전문가 스킬 업그레이드 — 창에서 그 스킬의 비용을 읽었으면 ΔG 를 기다리지 않는다.
            // 창을 열어둔 동안 골드 표시가 갱신되지 않아 대기창을 넘기고 통째로 누락되던 문제
            // (2026-08-13 제보). 스킬 이름으로 짝지으므로 여러 스킬을 올려도 정확하다.
            if (e.getKey() == TradeSignal.Type.SKILL_UPGRADE) {
                List<PendingSignal> unresolved = new ArrayList<>();
                for (PendingSignal ps : group) {
                    Long cost = skillCostFor(ps.sig.label);
                    if (cost != null && cost > 0) {
                        emitDeltaRecord(ps, sign, cost, "창 표시 금액");
                    } else {
                        unresolved.add(ps);
                    }
                }
                if (unresolved.isEmpty()) {
                    lastSettleInfo = "SKILL_UPGRADE " + group.size() + "건 — 창 표시 금액으로 기록";
                    signals.removeAll(group);
                    continue;
                }
                // 창 금액을 못 읽은 건만 기존 ΔG 경로로 넘긴다
                group = unresolved;
            }

            if (taken.isEmpty()) {
                // ΔG 를 못 봐도 창에 적힌 비용을 알고 있으면 그 금액으로 기록한다.
                // 수리 창을 열어둔 채 연속 작업하면 잔고 표시 갱신이 늦어 변동을 놓치는데,
                // 화면에 떠 있던 가격은 확실한 근거이므로 통째로 누락시키는 것보다 정확하다.
                long[] fromGui = recentCosts(e.getKey(), group.size());
                if (fromGui != null) {
                    for (int i = 0; i < group.size(); i++) {
                        emitDeltaRecord(group.get(i), sign, fromGui[i], "창 표시 금액");
                    }
                    lastSettleInfo = e.getKey() + " " + group.size() + "건 — 창 표시 금액으로 기록";
                } else {
                    emitUnresolved(e.getKey(), group, "잔고 변동 미검출");
                }
            } else {
                long total = 0;
                for (long d : taken) total += Math.abs(d);
                int n = group.size();

                long[] byGui = splitByGuiCosts(e.getKey(), total, n);
                if (byGui != null) {                       // 창에서 읽은 실제 비용으로 정확히 분해
                    for (int i = 0; i < n; i++) emitDeltaRecord(group.get(i), sign, byGui[i]);
                } else if (total % n == 0) {               // 회당 비용이 같은 정상 케이스
                    long each = total / n;
                    for (PendingSignal ps : group) emitDeltaRecord(ps, sign, each);
                } else {                                   // 비용이 다른 시도가 섞임 → 하나씩 배정
                    for (int i = 0; i < group.size() && i < taken.size(); i++) {
                        emitDeltaRecord(group.get(i), sign, Math.abs(taken.get(i)));
                    }
                }
                lastSettleInfo = e.getKey() + " " + group.size() + "건 — ΔG 로 기록";
            }
            signals.removeAll(group);
        }
    }

    /**
     * 실행할 때마다 비용이 달라지는 유형 — 합산 금액을 나눠 추정하면 안 된다.
     *
     *  · 도구 수리   : 손상도에 따라 매번 다름
     *  · 품질 회복   : 회복 횟수마다 10,000골드씩 오르는데, 그 횟수가 **도구마다 다르다**
     *                 (10번 회복한 도구는 110,000, 새 도구는 10,000) → 규칙으로 역산 불가
     *
     * 이런 유형은 균등 분할·등차수열 복원 모두 실제로 없던 금액을 만들어낸다.
     * 그래서 관측된 잔고 변동을 그대로만 기록한다(건수가 줄더라도 총액과 각 금액은 진짜다).
     */
    private static final java.util.EnumSet<TradeSignal.Type> VARIABLE_COST_TYPES = java.util.EnumSet.of(
            TradeSignal.Type.TOOL_REPAIR, TradeSignal.Type.QUALITY_RESTORE);

    /**
     * 창에 금액이 적혀 있으면 <b>ΔG 보다 우선</b>하는 유형. 창을 못 읽으면 기존 ΔG 경로로 넘어간다.
     *
     * <p>2026-08-14 제보 진단: 강화 창은 "강화 비용 : 5,000골드"인데 같은 순간 ΔG 는
     * -3,220,718 이었다(<b>644배</b>). 잔고 표시를 한 틱 잘못 읽으면 그 오차가 통째로 강화비가 된다 —
     * 수리에서 1.7억이 찍힌 것과 같은 실패 모드다.
     *
     * <p>수리처럼 "창 아니면 기록 안 함"으로 못 박지 않는 이유: 강화는 ΔG 경로가 실제로
     * 잘 동작하는 사용자가 있다(제작자 본인 확인). 창을 못 읽는 상황에서까지 버리면 손해다.
     */
    private static final java.util.EnumSet<TradeSignal.Type> GUI_COST_PREFERRED_TYPES =
            java.util.EnumSet.of(TradeSignal.Type.WEAPON_ENHANCE);

    /**
     * 창에서 읽어둔 비용들로 합산 금액을 n건으로 정확히 분해한다.
     * 예) 품질회복 2건 합산 120,000 — 창에서 110,000·10,000 을 봤다면 그대로 복원.
     * 규칙으로 추정하지 않고 **실제로 화면에 떠 있던 금액만** 조합하므로 없던 숫자가 생기지 않는다.
     *
     * @return 건별 금액, 조합을 못 찾으면 null
     */
    private long[] splitByGuiCosts(TradeSignal.Type type, long total, int n) {
        Map<Long, Long> seen = guiCosts.get(type);
        if (seen == null || seen.isEmpty() || n < 1) return null;
        List<Long> candidates = new ArrayList<>(seen.keySet());
        candidates.sort(java.util.Comparator.reverseOrder());
        List<Long> picked = new ArrayList<>();
        if (!pickCosts(total, n, candidates, 0, picked)) return null;
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = picked.get(i);
        return out;
    }

    /** 후보 비용에서 (중복 허용) 정확히 need 개를 골라 remain 을 맞춘다. */
    private boolean pickCosts(long remain, int need, List<Long> candidates, int from, List<Long> picked) {
        if (need == 0) return remain == 0;
        if (remain <= 0) return false;
        for (int i = from; i < candidates.size(); i++) {
            long c = candidates.get(i);
            if (c > remain) continue;
            picked.add(c);
            if (pickCosts(remain - c, need - 1, candidates, i, picked)) return true;
            picked.remove(picked.size() - 1);
        }
        return false;
    }

    /** 부호가 맞는 ΔG 를 전부 꺼낸다(합산 유입 대응). */
    private List<Long> takeAllDeltas(int sign) {
        List<Long> out = new ArrayList<>();
        Iterator<PendingDelta> it = deltas.iterator();
        while (it.hasNext()) {
            PendingDelta pd = it.next();
            if (Long.signum(pd.delta) != sign) continue;
            out.add(pd.delta);
            it.remove();
        }
        return out;
    }

    /**
     * 최근 창에서 본 비용 중 최신 n개(부족하면 가장 최근 값 반복).
     * 창을 열고 작업할 때마다 그 도구의 가격이 갱신되므로, 최근 값일수록 실제 지불액에 가깝다.
     * @return 없으면 null
     */
    /**
     * 수리·품질회복 전용 정산 — <b>창에 적힌 금액만</b> 근거로 쓴다.
     *
     * <p>순서: ① 창이 0골드였으면 무료 → 기록 없음 ② ΔG 총액을 창 금액 조합으로 쪼갤 수 있으면
     * 그대로 ③ 아니면 최근 창 금액을 사용 ④ 창 금액이 아예 없으면 <b>기록하지 않는다</b>.
     *
     * <p>④에서 예전엔 ΔG 를 그대로 적었는데, 잔고를 한 틱 잘못 읽으면 그 오차가 통째로
     * 수리비가 돼 버린다(실측: 0원 수리에 −170,901,145). 창을 열어야만 하는 작업이라
     * 금액을 못 읽었다는 건 이미 비정상이므로, 지어내느니 빠뜨리는 쪽이 안전하다.
     */
    private void settleByGuiCostOnly(TradeSignal.Type type, List<PendingSignal> group,
                                     int sign, List<Long> taken) {
        int n = group.size();
        if (sawZeroCost(type)) {
            LOG.info("[dtledger] {} {}건 — 창에 0골드(인챈트 없는 도구), 무료라 기록 안 함", type, n);
            lastSettleInfo = type + " " + n + "건 — 무료(0골드), 기록 안 함";
            return;
        }

        long[] costs = null;
        if (!taken.isEmpty()) {
            long total = 0;
            for (long d : taken) total += Math.abs(d);
            costs = splitByGuiCosts(type, total, n);   // ΔG 와 창 금액이 맞아떨어지면 최선
        }
        if (costs == null) costs = recentCosts(type, n);

        if (costs == null) {
            emitUnresolved(type, group, "창 금액을 못 읽음");
            return;
        }
        for (int i = 0; i < n; i++) {
            emitDeltaRecord(group.get(i), sign, costs[i], "창 표시 금액");
        }
        lastSettleInfo = type + " " + n + "건 — 창 표시 금액으로 기록";
    }

    /** 이 유형이 "0골드(무료)"로 마지막에 관측됐는가 — 그 뒤에 유료 관측이 없으면 무료로 본다. */
    private boolean sawZeroCost(TradeSignal.Type type) {
        Long zeroAt = zeroCostSeenAt.get(type);
        if (zeroAt == null) return false;
        return System.currentTimeMillis() - zeroAt <= GUI_COST_MEMORY_MS;
    }

    /**
     * 창에서 읽어둔 비용 중 <b>최근 것만</b> 쓴다.
     * 2분 전 다른 도구의 수리비를 지금 수리에 붙이면 없는 지출이 생기므로,
     * 이 작업과 같은 시점에 화면에 떠 있었다고 볼 수 있는 것만 인정한다(2026-08-08 제보).
     */
    private static final long GUI_COST_FRESH_MS = 30_000;

    private long[] recentCosts(TradeSignal.Type type, int n) {
        Map<Long, Long> seen = guiCosts.get(type);
        if (seen == null || seen.isEmpty() || n < 1) return null;
        long now = System.currentTimeMillis();
        seen = new java.util.HashMap<>(seen);
        seen.entrySet().removeIf(en -> now - en.getValue() > GUI_COST_FRESH_MS);
        if (seen.isEmpty()) return null;
        List<Map.Entry<Long, Long>> sorted = new ArrayList<>(seen.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue(), a.getValue())); // 최신 먼저
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = sorted.get(Math.min(i, sorted.size() - 1)).getKey();
        }
        return out;
    }

    /** ΔG금액형 신호 1건을 레코드로. amount 는 합산 분할 결과일 수 있어 따로 받는다. */
    /** 금액을 못 알아낸 거래를 사용자에게 알리는 통로(로더가 채팅으로 연결). 기본은 무동작. */
    private java.util.function.Consumer<String> notifier = msg -> { };

    public void setNotifier(java.util.function.Consumer<String> n) {
        if (n != null) this.notifier = n;
    }

    /**
     * 금액을 끝내 알아내지 못한 거래를 <b>버리지 않고</b> 금액 0 · 미확인으로 남긴다.
     *
     * <p>2026-08-13 제보: 전문가 스킬 업그레이드 지출이 통째로 빠졌는데 유저는 총액을 직접
     * 대조하기 전까지 알 수 없었다("어쩔 수 없는 부분이면 아쉽네요"). 조용히 버리면
     * <b>빠졌다는 사실 자체가 안 보인다.</b> 흔적을 남기면 유저는 바로 알아채고,
     * 제보도 "왜 없죠?"가 아니라 "미확인으로 떴어요"가 되어 원인 파악이 빨라진다.
     *
     * <p>금액 0 이라 합계·손익을 오염시키지 않는다. 실제 금액은
     * {@code /빌띵 추가 지출 <금액> <내용>} 으로 넣고 이 줄은 Shift+클릭으로 지우면 된다.
     */
    private void emitUnresolved(TradeSignal.Type type, List<PendingSignal> group, String reason) {
        if (group.isEmpty()) return;
        if (!config.recordUnresolved) {
            LOG.info("[dtledger] {} {}건 — {} (미확인 기록 꺼짐)", type, group.size(), reason);
            return;
        }
        for (PendingSignal ps : group) {
            int sign = ps.sig.expectedSign();
            TransactionRecord.Kind kind = sign < 0
                    ? TransactionRecord.Kind.EXPENSE : TransactionRecord.Kind.INCOME;
            emit(new TransactionRecord(ps.ts, kind, 0, categoryOf(ps.sig.type), ps.sig.label, 0,
                    false, TransactionRecord.Confidence.LOW, false,
                    "금액 미확인(" + reason + ") — /빌띵 추가 로 입력 후 이 줄 삭제"));
        }
        String label = group.get(0).sig.label;
        notifier.accept("§6[빌띵] §f" + label + " §7금액을 확인하지 못했습니다"
                + (group.size() > 1 ? " (" + group.size() + "건)" : "")
                + "\n§7  내역에 §e미확인§7 으로 남겼습니다. "
                + "§f/빌띵 추가 " + (kindWord(group.get(0).sig)) + " <금액> " + label);
        lastSettleInfo = type + " " + group.size() + "건 — 미확인으로 기록(" + reason + ")";
    }

    private static String kindWord(TradeSignal sig) {
        return sig.expectedSign() < 0 ? "지출" : "수입";
    }

    private static String categoryOf(TradeSignal.Type type) {
        return switch (type) {
            case WEAPON_ENHANCE -> "강화";
            case ENGRAVE -> "각인";
            case TOOL_REPAIR, QUALITY_RESTORE -> "수리";
            case QUEST_REWARD -> "의뢰";
            default -> "전문가";
        };
    }

    private void emitDeltaRecord(PendingSignal ps, int sign, long amount) {
        emitDeltaRecord(ps, sign, amount, null);
    }

    private void emitDeltaRecord(PendingSignal ps, int sign, long amount, String note) {
        TransactionRecord.Kind kind = sign < 0
                ? TransactionRecord.Kind.EXPENSE : TransactionRecord.Kind.INCOME;
        String cat = switch (ps.sig.type) {
            case WEAPON_ENHANCE -> "강화";
            case ENGRAVE -> "각인";
            case TOOL_REPAIR, QUALITY_RESTORE -> "수리";
            case QUEST_REWARD -> "의뢰";
            default -> "전문가"; // SKILL_UPGRADE
        };
        // 잔고 변동으로 확인한 건 HIGH, 창 표시 금액으로 채운 건 MEDIUM(교차검증 안 됨)
        boolean crossChecked = note == null;
        emit(new TransactionRecord(ps.ts, kind, amount, cat, ps.sig.label, 0, true,
                crossChecked ? TransactionRecord.Confidence.HIGH : TransactionRecord.Confidence.MEDIUM,
                crossChecked, note));
    }

    /**
     * 조건에 맞는 델타 하나를 버퍼에서 꺼낸다.
     * @param sign 기대 부호(+1/-1)
     * @param magnitude 기대 절대금액. 음수면 금액 무시(부호만).
     * @param exact true면 금액 정확 일치 요구.
     */
    private Long takeDelta(int sign, long magnitude, boolean exact) {
        Iterator<PendingDelta> it = deltas.iterator();
        while (it.hasNext()) {
            PendingDelta pd = it.next();
            if (Long.signum(pd.delta) != sign) continue;
            if (exact && Math.abs(pd.delta) != magnitude) continue;
            it.remove();
            return pd.delta;
        }
        return null;
    }

    private void emit(TransactionRecord rec) {
        if (rec.confidence == TransactionRecord.Confidence.MEDIUM
                && "금액 불일치(판매세/수수료 의심)".equals(rec.note)) {
            LOG.warn("[dtledger] 금액-ΔG 불일치: {}", rec);
        } else {
            LOG.info("[dtledger] 레코드: {}", rec);
        }
        sink.accept(rec);
    }
}
