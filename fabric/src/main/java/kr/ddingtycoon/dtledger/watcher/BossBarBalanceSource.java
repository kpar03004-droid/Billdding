package kr.ddingtycoon.dtledger.watcher;

import kr.ddingtycoon.dtledger.mixin.BossBarHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;

import java.util.ArrayList;
import java.util.List;

/** 보스바 텍스트 수집(accessor mixin 경유). */
public final class BossBarBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "bossbar";
    }

    @Override
    public List<String> lines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        try {
            BossBarHud hud = client.inGameHud.getBossBarHud();
            if (hud == null) return out;
            for (ClientBossBar bar : ((BossBarHudAccessor) hud).getBossBars().values()) {
                if (bar.getName() != null) out.add(bar.getName().getString());
            }
        } catch (Throwable t) {
            // 크래시 금지
        }
        return out;
    }
}
