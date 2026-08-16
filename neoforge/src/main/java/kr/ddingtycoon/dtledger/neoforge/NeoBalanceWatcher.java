package kr.ddingtycoon.dtledger.neoforge;

import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.neoforge.mixin.PlayerTabOverlayAccessor;
import kr.ddingtycoon.dtledger.util.BalanceExtractor;
import kr.ddingtycoon.dtledger.util.GoldFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * NeoForge 판 잔고 감지 (mojmap). 추출 로직은 common 의 BalanceExtractor 공유.
 * 보스바는 mixin 대신 CustomizeGuiOverlayEvent.BossEventProgress 캡처로 수집.
 */
public final class NeoBalanceWatcher {
    private static final Logger LOG = LoggerFactory.getLogger("dtledger");
    private static final long BOSSBAR_TTL_MS = 3000; // 렌더 이벤트 기반 — 잠시 안 보이면 제거

    private final DtConfig config;
    private final LongConsumer deltaListener;
    private final BalanceExtractor extractor = new BalanceExtractor();

    // 새 값이 이만큼 연속 같아야 확정(노이즈 억제).
    // 2026-08-11: 깜빡임을 더 거르겠다고 5로 올렸다가 ΔG 가 아예 안 잡혀 되돌림.
    //   잔고 소스는 화면 최대 숫자라 틱마다 값이 흔들릴 수 있어, 요구 틱을 올리면
    //   후보가 매번 초기화돼 영영 확정되지 않는다. 검증 없이 건드리지 말 것.
    private static final int CONFIRM_TICKS = 2;

    /**
     * 지금 잔고를 얼마로 읽고 있는지(진단용, /빌띵 진단).
     * "ΔG 가 안 잡힌다"가 <b>못 읽는 것</b>인지 <b>값이 안 변하는 것</b>인지 구분하려면 이게 필요하다.
     */
    private static volatile String lastReadInfo;

    public static String lastReadInfo() { return lastReadInfo; }

    private static final int SETTLE_TICKS = 40;   // 월드 전환 후 이만큼 기준선만 따라감(로드 중 오탐 방지)

    private final Map<String, Long> bossBarLines = new LinkedHashMap<>(); // 텍스트 → 마지막 목격 시각
    private Long lastBalance = null;
    private Long candidate = null;
    private int candidateTicks = 0;
    private int settleTicks = 0;
    private Object lastWorld = null; // ClientLevel 식별 — 월드/서버 전환 감지
    private volatile String lastActionBar = "";

    public NeoBalanceWatcher(DtConfig config, LongConsumer deltaListener) {
        this.config = config;
        this.deltaListener = deltaListener;
    }

    public void captureActionBar(String text) {
        this.lastActionBar = text == null ? "" : text;
    }

    /** 보스바 렌더 이벤트에서 호출 — 이름 텍스트 캡처. */
    public void captureBossBar(String name) {
        if (name != null && !name.isEmpty()) {
            bossBarLines.put(name, System.currentTimeMillis());
        }
    }

    public void reset() {
        this.lastBalance = null;
        this.candidate = null;
        this.candidateTicks = 0;
        this.extractor.unlock();   // 서버가 바뀌면 골드 표시 모양도 달라질 수 있다
    }

    public void tick(Minecraft mc, long now) {
        if (mc.player == null || mc.level == null) return;
        bossBarLines.values().removeIf(t -> now - t > BOSSBAR_TTL_MS);

        // 월드/서버 전환(스폰↔마을 등)이면 기준선 리셋 후 정착 구간 시작 — 이동은 거래가 아님.
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            reset();
            settleTicks = SETTLE_TICKS;
            return;
        }

        Long bal = readBalance(mc);
        if (bal == null) {
            lastReadInfo = "§c읽기 실패(화면에서 금액을 못 찾음)";
            return;
        }
        lastReadInfo = "확정 " + GoldFormat.format(lastBalance == null ? bal : lastBalance)
                + (candidate != null ? " · 후보 " + GoldFormat.format(candidate) + " (" + candidateTicks + "틱)" : "")
                + " · 방금 읽음 " + GoldFormat.format(bal)
                + (extractor.lockedShape() != null ? " · 추적중인 줄 [" + extractor.lockedShape() + "]" : "");

        if (settleTicks > 0) {
            settleTicks--;
            lastBalance = bal;
            candidate = null;
            candidateTicks = 0;
            return;
        }

        if (lastBalance == null) {
            lastBalance = bal;
            return;
        }
        if (bal.equals(lastBalance)) {
            candidate = null;
            candidateTicks = 0;
            return;
        }
        if (bal.equals(candidate)) {
            if (++candidateTicks >= CONFIRM_TICKS) {
                long delta = bal - lastBalance;
                lastBalance = bal;
                candidate = null;
                candidateTicks = 0;
                LOG.info("[dtledger] ΔG = {} (now={})", GoldFormat.signed(delta), GoldFormat.format(bal));
                deltaListener.accept(delta);
            }
        } else {
            candidate = bal;
            candidateTicks = 1;
        }
    }

    private Long readBalance(Minecraft mc) {
        List<String> lines = new ArrayList<>();
        String mode = config.balanceSourceMode == null ? "AUTO" : config.balanceSourceMode.toUpperCase();
        switch (mode) {
            case "SCOREBOARD" -> lines.addAll(scoreboardLines(mc));
            case "BOSSBAR" -> lines.addAll(bossBarLines.keySet());
            case "TABLIST" -> lines.addAll(tabListLines(mc));
            case "ACTIONBAR" -> lines.add(lastActionBar);
            default -> { // AUTO
                lines.addAll(scoreboardLines(mc));
                lines.addAll(bossBarLines.keySet());
                lines.addAll(tabListLines(mc));
                lines.add(lastActionBar);
            }
        }
        return extractor.extract(lines, config.balanceMarker, config.balanceRegex);
    }

    List<String> scoreboardLines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        try {
            if (mc.level == null) return out;
            Scoreboard sb = mc.level.getScoreboard();
            Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) return out;
            out.add(obj.getDisplayName().getString());
            for (PlayerScoreEntry entry : sb.listPlayerScores(obj)) {
                String owner = entry.owner();
                PlayerTeam team = sb.getPlayersTeam(owner);
                String decorated = PlayerTeam.formatNameForTeam(team, Component.literal(owner)).getString();
                out.add(decorated + " " + entry.value());
            }
        } catch (Throwable t) {
            // 크래시 금지 — probe 단계에서 확인
        }
        return out;
    }

    List<String> tabListLines(Minecraft mc) {
        List<String> out = new ArrayList<>();
        try {
            PlayerTabOverlayAccessor acc = (PlayerTabOverlayAccessor) mc.gui.getTabList();
            Component header = acc.getHeader();
            Component footer = acc.getFooter();
            if (header != null) out.add(header.getString());
            if (footer != null) out.add(footer.getString());
        } catch (Throwable t) {
            // 크래시 금지
        }
        return out;
    }

    List<String> bossBarSnapshot() {
        return new ArrayList<>(bossBarLines.keySet());
    }
}
