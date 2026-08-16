package kr.ddingtycoon.dtledger.watcher;

import kr.ddingtycoon.dtledger.mixin.PlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** 탭리스트 header/footer 텍스트 수집(accessor mixin 경유). */
public final class TabListBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "tablist";
    }

    @Override
    public List<String> lines(MinecraftClient client) {
        List<String> out = new ArrayList<>();
        try {
            PlayerListHud hud = client.inGameHud.getPlayerListHud();
            if (hud == null) return out;
            PlayerListHudAccessor acc = (PlayerListHudAccessor) hud;
            Text header = acc.getHeader();
            Text footer = acc.getFooter();
            if (header != null) out.add(header.getString());
            if (footer != null) out.add(footer.getString());
        } catch (Throwable t) {
            // 크래시 금지
        }
        return out;
    }
}
