package kr.ddingtycoon.dtledger.ui;

import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.config.DtConfigScreen;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** HUD 토글 / 정산 창 / 설정 키바인드(기본 미할당). */
public final class DtKeyBindings {
    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final VaultTracker vault;
    private final LedgerHud hud;
    private final java.util.function.Consumer<kr.ddingtycoon.dtledger.core.TransactionRecord> sink;

    private KeyBinding toggleHud;
    private KeyBinding openStats;
    private KeyBinding openConfig;
    private KeyBinding editHud;

    public DtKeyBindings(DtConfig config, DailyAggregator aggregator, VaultTracker vault, LedgerHud hud,
                         java.util.function.Consumer<kr.ddingtycoon.dtledger.core.TransactionRecord> sink) {
        this.config = config;
        this.aggregator = aggregator;
        this.vault = vault;
        this.hud = hud;
        this.sink = sink;
    }

    public void register() {
        toggleHud = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "dtledger.key.toggle_hud", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "dtledger.key.category"));
        openStats = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "dtledger.key.open_stats", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "dtledger.key.category"));
        openConfig = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "dtledger.key.open_config", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "dtledger.key.category"));
        editHud = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "dtledger.key.edit_hud", InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, "dtledger.key.category"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(MinecraftClient client) {
        while (toggleHud.wasPressed()) {
            config.hudEnabled = !config.hudEnabled;
            config.save();
        }
        while (openStats.wasPressed()) {
            client.setScreen(new DtStatScreen(config, aggregator, vault, sink, hud));
        }
        while (editHud.wasPressed()) {
            client.setScreen(new DtHudEditScreen(config, hud));
        }
        while (openConfig.wasPressed()) {
            Screen screen = DtConfigScreen.create(config, client.currentScreen);
            if (screen != null) client.setScreen(screen); // YACL 없으면 현재 화면 유지
        }
    }
}
