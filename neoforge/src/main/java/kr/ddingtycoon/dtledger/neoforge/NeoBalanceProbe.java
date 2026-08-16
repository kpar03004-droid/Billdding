package kr.ddingtycoon.dtledger.neoforge;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * STEP1 진단 로거 (NeoForge 판) — 잔고 소스 A/B 판정용.
 * 채팅/액션바는 엔트리의 이벤트 리스너가 log() 로 전달, 표면 3종은 1초마다 덤프.
 * config.debugProbe=false 로 끔.
 */
public final class NeoBalanceProbe {
    private static final Logger LOG = LoggerFactory.getLogger("dtledger-probe");

    private final NeoBalanceWatcher watcher;
    private long lastLog = 0;

    public NeoBalanceProbe(NeoBalanceWatcher watcher) {
        this.watcher = watcher;
        LOG.info("BalanceProbe 활성화 — 1초마다 소스 라인을 로그합니다. 골드값을 찾아 소스를 확정하세요.");
    }

    public void log(String src, String line) {
        LOG.info("[{}] {}", src, line);
    }

    public void tick(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - lastLog < 1000) return;
        lastLog = now;
        if (mc.player == null || mc.level == null) return;

        dump("scoreboard", watcher.scoreboardLines(mc));
        dump("bossbar", watcher.bossBarSnapshot());
        dump("tablist", watcher.tabListLines(mc));
    }

    private void dump(String src, List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            LOG.info("[{}][{}] {}", src, i, lines.get(i));
        }
    }
}
