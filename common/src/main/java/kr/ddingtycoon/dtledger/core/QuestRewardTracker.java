package kr.ddingtycoon.dtledger.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 일일/주간 의뢰 보상(수입) 감지.
 *
 * 과거 방식(채팅 "의뢰를 완료하였습니다" → 부호 맞는 아무 +ΔG 채택)은 같은 순간의 판매·송금
 * 대금을 통째로 의뢰 보상으로 오기록했다(실측 +93,285,292). 수입은 경쟁자가 너무 많아
 * "아무 +ΔG"를 잡는 접근 자체가 성립하지 않는다.
 *
 * 그래서 의뢰 게시판 GUI 를 근거로 삼는다 — 각 의뢰 아이템 설명(lore)에
 * "- 보상 : 20,000골드"가 그대로 적혀 있으므로 창을 읽으면
 *   (1) 여기가 의뢰 창인지  (2) 각 의뢰의 정확한 보상액
 * 을 알 수 있고, 그 금액과 정확히 일치하는 +ΔG 만 의뢰로 인정한다.
 *
 * 보상은 창에서 클릭해 수령하므로 "창이 열려 있다"는 조건이 자연스럽게 성립한다.
 * 창에서 못 읽었을 때를 대비해 위키 보상표 값 목록을 보조 판정으로 둔다.
 * 루비 보상은 골드가 아니므로 집계하지 않는다.
 */
public final class QuestRewardTracker {

    /** 창을 닫은 직후에도 ΔG(잔고 표시 갱신 지연)를 잡기 위한 유예. */
    // 2026-07-29: 미집계 원인은 타이밍이 아니라 창 서명 매칭 실패였음(창을 바로 닫아도 안 됐음).
    // 수입 쪽이라 유예가 길면 같은 금액의 판매 대금을 잘못 가져갈 위험이 커져 15초로 되돌림.
    private static final long GRACE_MS = 15_000;
    /** 수령 후 목록이 갱신돼도 직전에 본 보상액을 이만큼 유효 취급. */
    private static final long REWARD_MEMORY_MS = 30_000;

    /**
     * 이 창임을 알리는 서명 — 아이템 이름("[ 문어 채집하기 일일 의뢰 ]")과
     * lore("- 의뢰 진행도 : 0 / 30") 양쪽에 들어있다.
     * "의뢰 진행도"로 좁혔더니 창 감지가 안 된다는 제보(2026-07-29)가 있어 "의뢰"로 완화.
     * 서명만으로 기록하지 않고 "보상 : N골드"까지 읽혀야 후보가 되므로 오탐 위험은 낮다.
     */
    public static final String GUI_SIGNATURE = "의뢰";

    private static final Pattern REWARD_LINE = Pattern.compile("보상\\s*[:：]\\s*([\\d,]+)\\s*골드");

    /**
     * 수령 완료 표시 — 2026-07-29 실측: 보상을 받은 의뢰는 lore 가
     * "❗ 이미 완료한 의뢰입니다."로 바뀐다(받기 전엔 "의뢰를 먼저 완료해주세요").
     * 이 전환을 보면 "언제 수령했는지"를 추측이 아니라 직접 알 수 있다.
     */
    public static final String CLAIMED_MARKER = "이미 완료한 의뢰";

    /**
     * 창에서 읽은 의뢰 한 건. claimed=보상 수령 완료 상태.
     * complete=진행도를 다 채웠는가("- 의뢰 진행도 : 30 / 30"). 진행도 줄을 못 읽었으면 true(판단 보류).
     */
    public record Entry(String quest, long reward, boolean claimed, boolean complete) {
        public Entry(String quest, long reward, boolean claimed) { this(quest, reward, claimed, true); }
    }

    private static final Pattern PROGRESS_LINE =
            Pattern.compile("진행도\\s*[:：]\\s*([\\d,]+)\\s*/\\s*([\\d,]+)");

    /**
     * lore 한 줄의 진행도. "- 의뢰 진행도 : 12 / 30" → 완료 여부(12≥30? false).
     * 진행도 줄이 아니면 null.
     */
    public static Boolean parseProgressDone(String loreLine) {
        if (loreLine == null) return null;
        Matcher m = PROGRESS_LINE.matcher(loreLine);
        if (!m.find()) return null;
        long done = parseNumber(m.group(1)), goal = parseNumber(m.group(2));
        return goal > 0 && done >= goal;
    }

    /** lore 한 줄이 수령 완료 표시인가. */
    public static boolean isClaimedLine(String loreLine) {
        return loreLine != null && loreLine.contains(CLAIMED_MARKER);
    }

    /**
     * 위키 의뢰 보상표에 등장하는 골드 값(일일·주간 전 등급).
     * 창을 못 읽은 경우의 보조 판정용 — 이 목록 밖 금액은 절대 의뢰로 인정하지 않아
     * 판매 대금이 통째로 의뢰로 잡히는 사고를 원천 차단한다.
     */
    private static final Set<Long> KNOWN_REWARDS = Set.of(
            10_000L, 14_000L, 20_000L, 28_000L, 30_000L, 40_000L, 50_000L,
            60_000L, 70_000L, 100_000L, 140_000L, 150_000L, 200_000L, 300_000L, 400_000L);

    /** lore 한 줄에서 "보상 : N골드"의 N을 추출. 없으면 0(루비 보상 줄은 0). */
    public static long parseRewardGold(String loreLine) {
        if (loreLine == null) return 0;
        Matcher m = REWARD_LINE.matcher(loreLine);
        return m.find() ? parseNumber(m.group(1)) : 0;
    }

    /** "[ 문어 채집하기 일일 의뢰 ]" → "문어 채집하기 일일 의뢰". */
    public static String questLabel(String rawName) {
        if (rawName == null) return "";
        String s = rawName.replaceAll("§.", "").trim();
        s = s.replaceFirst("^\\[\\s*", "").replaceFirst("\\s*\\]$", "");
        return s.trim().replaceAll("\\s+", " ");
    }

    private static long parseNumber(String s) {
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static final class Seen {
        final String label;
        final long ts;
        Seen(String label, long ts) { this.label = label; this.ts = ts; }
    }

    private final Consumer<TransactionRecord> sink;
    private final Map<Long, Seen> recentRewards = new HashMap<>();
    /** 의뢰명 → 직전에 본 수령 여부. 전환(미수령→수령)을 잡기 위한 상태 기억. */
    private final Map<String, Boolean> claimedState = new HashMap<>();
    /**
     * 수령 감지로 이미 기록한 보상들 {금액, 시각} — 뒤따라오는 ΔG 를 삼켜 이중계상을 막는다.
     *
     * <p>⚠️ 금액을 키로 쓰는 Map 이면 안 된다(2026-09-28 제보 원인). 레드스톤 50,000 과
     * 청금석 50,000 을 연달아 받으면 "50,000 한 건"으로 뭉쳐 합산 ΔG(+100,000)를 못 삼키고,
     * 아래 분해 경로가 그걸 다시 쪼개 레드스톤 ×3 으로 기록했다. 같은 금액이 여러 건일 수 있다.
     */
    private final List<long[]> pendingClaims = new ArrayList<>();
    /** ΔG 경로로 이미 기록한 보상들 {금액, 시각} — 수령 전환이 ΔG 보다 늦게 보여도 두 번 적지 않게. */
    private final List<long[]> deltaRecorded = new ArrayList<>();
    private volatile long lastGuiTs = 0;
    /** 마지막으로 감지한 수령(진단용, /빌띵 진단). 감지가 한 번도 안 됐으면 null. */
    private static volatile String lastClaimDetected;

    public static String lastClaimDetected() {
        return lastClaimDetected;
    }

    public QuestRewardTracker(Consumer<TransactionRecord> sink) {
        this.sink = sink;
    }

    /**
     * 로더 화면 감시기가 매 틱 호출. 창에서 읽은 의뢰 목록을 넘긴다.
     *
     * 핵심: "미수령 → 수령 완료"로 바뀌는 순간이 곧 보상 수령 시점이고, 그 의뢰의 보상액은
     * 같은 아이템 설명에 적혀 있으므로 **금액이 확정**된다. 그래서 잔고 변동을 기다리지 않고
     * 바로 기록한다(추측 제거) — 판매 대금을 훔치거나, 여러 건이 합산돼 놓치는 문제가 사라진다.
     *
     * @param entries 열린 의뢰 창의 의뢰들. 의뢰 창이 아니면 null/빈 목록.
     */
    public synchronized void updateGui(java.util.List<Entry> entries) {
        if (entries == null || entries.isEmpty()) return;
        long now = System.currentTimeMillis();
        lastGuiTs = now;

        expire(now);
        for (Entry e : entries) {
            if (e.quest() == null || e.quest().isBlank()) continue;
            Boolean prev = claimedState.get(e.quest());
            // 처음 본 의뢰는 전환이 아님(이미 수령 상태로 열었을 수 있음) → 기록하지 않는다.
            if (prev != null && !prev && e.claimed() && e.reward() > 0) {
                // 잔고 변동이 전환보다 먼저 와서 ΔG 경로로 이미 적었다면 또 적지 않는다.
                if (!removeOne(deltaRecorded, e.reward())) {
                    sink.accept(new TransactionRecord(now, TransactionRecord.Kind.INCOME, e.reward(),
                            "의뢰", e.quest(), 0,
                            true, TransactionRecord.Confidence.HIGH, false, "수령 감지"));
                    pendingClaims.add(new long[]{e.reward(), now});
                }
                lastClaimDetected = e.quest() + " (" + e.reward() + ")"; // 진단용
            }
            claimedState.put(e.quest(), e.claimed());
            // 보조 판정 후보는 '진행도를 다 채웠고 아직 안 받은' 의뢰만.
            //   이미 받은 의뢰는 다시 돈을 줄 수 없고, 진행도가 안 찬 의뢰는 아직 돈을 줄 수 없다.
            //   (2026-09-28 제보: 의뢰를 하나도 안 했는데 창에 떠 있던 굴 채집 10,000 으로
            //    무관한 +10,000 잔고 변동이 의뢰 수입으로 기록됨)
            if (e.reward() > 0 && !e.claimed() && e.complete()) {
                noteCandidate(e.reward(), e.quest(), now);
                lastClaimableTs = now;
            }
        }
    }

    /** 받을 수 있는(완료·미수령) 의뢰를 마지막으로 본 시각. 없으면 ΔG 를 의뢰로 볼 근거가 없다. */
    private long lastClaimableTs = 0;

    /**
     * 보조 판정 후보 등록. 같은 금액의 <b>다른</b> 의뢰가 둘 이상 보이면 어느 쪽인지 알 수 없으므로
     * 이름을 비워(null) "의뢰 완료"로 기록한다 — 금 채광 10,000 을 받았는데 창에 같이 떠 있던
     * 굴 채집 10,000 의 이름이 붙던 문제(2026-09-28 제보: "하지 않은 의뢰가 완료됨").
     */
    private void noteCandidate(long reward, String quest, long now) {
        Seen old = recentRewards.get(reward);
        boolean ambiguous = old != null && (old.label == null || !old.label.equals(quest));
        recentRewards.put(reward, new Seen(ambiguous ? null : quest, now));
    }

    private void expire(long now) {
        recentRewards.entrySet().removeIf(en -> now - en.getValue().ts > REWARD_MEMORY_MS);
        pendingClaims.removeIf(c -> now - c[1] > REWARD_MEMORY_MS);
        deltaRecorded.removeIf(c -> now - c[1] > REWARD_MEMORY_MS);
    }

    /** list 에서 금액이 같은 항목 하나를 뺀다. 뺐으면 true. */
    private static boolean removeOne(List<long[]> list, long amount) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[0] == amount) { list.remove(i); return true; }
        }
        return false;
    }

    /** 의뢰 창이 (유예 포함) 활성인가. */
    public boolean isActive(long now) {
        return now - lastGuiTs <= GRACE_MS;
    }

    /**
     * ΔG 를 의뢰 보상으로 소비 시도.
     * @return true=기록·소비함(호출측은 Resolver로 넘기지 말 것). false=해당 없음.
     */
    public synchronized boolean tryConsume(long delta) {
        long now = System.currentTimeMillis();
        if (!isActive(now)) return false;   // 의뢰 창 안 열림 → 판매 대금 등을 훔치지 않음
        if (delta <= 0) return false;       // 보상은 수입만

        long amount = delta;
        expire(now);

        // 수령 감지로 이미 기록한 건이면 뒤따라온 잔고 변동은 삼키기만 한다(이중계상 방지).
        // 여러 건을 연달아 받으면 잔고가 합쳐 들어오므로, 합계와 일치하는 경우도 함께 삼킨다.
        if (swallowRecorded(amount)) return true;

        // 수령 감지로 적어둔 보상이 아직 남아 있는데 그 조합으로 설명이 안 되는 잔고 변동이면
        // 의뢰로 또 적지 않는다. 수령 감지 쪽이 금액·이름이 확정된 기록이고, 여기서 한 번 더 적으면
        // 그게 곧 이중계상이다(원장 실측: 잔고 변동 경로 의뢰 7건 중 6건이 수령 감지와 겹친 중복).
        if (!pendingClaims.isEmpty()) return false;

        // 받을 수 있는 의뢰(진행도 완료·미수령)가 창에 없었다면 이 잔고 변동은 의뢰 보상일 수 없다.
        // 아래 보상표 안전망(10,000 등 흔한 금액)도 여기서 같이 막힌다.
        if (now - lastClaimableTs > REWARD_MEMORY_MS) return false;

        Seen seen = recentRewards.get(amount);
        if (seen != null) { // 한 건과 정확히 일치 — 이름이 겹치지 않으면 의뢰명까지 확정
            emit(now, amount, seen.label, true);
            return true;
        }

        // 여러 건을 연달아 받으면 잔고가 한 번에 합쳐 반영된다(실측: 20,000+50,000=70,000).
        // 창에서 읽은 보상액들의 합으로 쪼개지면 각각 따로 기록해 "뭉침"을 푼다.
        List<Seen> parts = decompose(amount);
        if (parts != null) {
            for (Seen p : parts) emit(now, findReward(p), p.label, true);
            return true;
        }

        // 창에서 못 읽은 경우의 마지막 보루 — 위키 보상표 값만 인정(그 외는 판매일 수 있어 거부).
        if (!KNOWN_REWARDS.contains(amount)) return false;
        emit(now, amount, "의뢰 완료", true);
        return true;
    }

    /**
     * 수령 감지로 이미 기록한 보상들의 (단건 또는 합계)와 일치하면 그만큼 소진하고 true.
     * 같은 금액이 여러 건 남아 있어도 각각 한 번씩 쓴다(50,000 + 50,000 = 100,000).
     */
    private boolean swallowRecorded(long amount) {
        if (pendingClaims.isEmpty()) return false;
        if (removeOne(pendingClaims, amount)) return true; // 단건 일치

        List<Integer> picked = new ArrayList<>();
        if (!searchSum(amount, 0, picked)) return false;
        picked.sort(java.util.Comparator.reverseOrder()); // 뒤에서부터 지워야 인덱스가 안 밀린다
        for (int idx : picked) pendingClaims.remove(idx);
        return true;
    }

    /** pendingClaims 에서 골라 remain 을 정확히 채우는 조합(각 항목 1회, 최대 6건). 고른 인덱스를 picked 에. */
    private boolean searchSum(long remain, int from, List<Integer> picked) {
        if (remain == 0) return !picked.isEmpty();
        if (picked.size() >= 6 || remain < 0) return false;
        for (int i = from; i < pendingClaims.size(); i++) {
            long a = pendingClaims.get(i)[0];
            if (a > remain) continue;
            picked.add(i);
            if (searchSum(remain - a, i + 1, picked)) return true;
            picked.remove(picked.size() - 1);
        }
        return false;
    }

    private void emit(long ts, long amount, String label, boolean crossChecked) {
        sink.accept(new TransactionRecord(ts, TransactionRecord.Kind.INCOME, amount,
                "의뢰", label == null || label.isBlank() ? "의뢰 완료" : label, 0,
                true, TransactionRecord.Confidence.HIGH, crossChecked, null));
        deltaRecorded.add(new long[]{amount, ts});
    }

    /** Seen 은 라벨만 갖고 있어 금액을 되찾는다(합산 분해 결과 기록용). */
    private long findReward(Seen target) {
        for (Map.Entry<Long, Seen> e : recentRewards.entrySet()) {
            if (e.getValue() == target) return e.getKey();
        }
        return 0;
    }

    /**
     * 합산된 금액을 창에서 본 보상액들의 조합으로 분해. 최대 4건까지만 시도(오탐·연산 억제).
     * @return 분해 성공 시 구성 요소들, 실패하면 null
     */
    private List<Seen> decompose(long total) {
        List<Long> amounts = new ArrayList<>(recentRewards.keySet());
        amounts.sort(java.util.Comparator.reverseOrder());
        List<Seen> picked = new ArrayList<>();
        return search(total, amounts, 0, picked) ? picked : null;
    }

    private boolean search(long remain, List<Long> amounts, int from, List<Seen> picked) {
        if (remain == 0) return picked.size() >= 2; // 한 건짜리는 위에서 이미 처리됨
        if (picked.size() >= 4 || remain < 0) return false;
        for (int i = from; i < amounts.size(); i++) {
            long a = amounts.get(i);
            if (a > remain) continue;
            picked.add(recentRewards.get(a));
            if (search(remain - a, amounts, i, picked)) return true; // 같은 금액 중복 허용
            picked.remove(picked.size() - 1);
        }
        return false;
    }
}
