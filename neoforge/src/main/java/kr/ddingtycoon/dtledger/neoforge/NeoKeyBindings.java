package kr.ddingtycoon.dtledger.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** HUD 토글 / 정산 창 키바인드 (NeoForge 판, 기본 미할당). */
public final class NeoKeyBindings {
    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final VaultTracker vault;
    private final NeoLedgerHud hud;
    private final java.util.function.Consumer<kr.ddingtycoon.dtledger.core.TransactionRecord> sink;

    private final KeyMapping toggleHud = new KeyMapping(
            "dtledger.key.toggle_hud", InputConstants.UNKNOWN.getValue(), "dtledger.key.category");
    private final KeyMapping openStats = new KeyMapping(
            "dtledger.key.open_stats", InputConstants.UNKNOWN.getValue(), "dtledger.key.category");
    private final KeyMapping editHud = new KeyMapping(
            "dtledger.key.edit_hud", InputConstants.UNKNOWN.getValue(), "dtledger.key.category");

    public NeoKeyBindings(DtConfig config, DailyAggregator aggregator, VaultTracker vault, NeoLedgerHud hud,
                          java.util.function.Consumer<kr.ddingtycoon.dtledger.core.TransactionRecord> sink) {
        this.config = config;
        this.aggregator = aggregator;
        this.vault = vault;
        this.hud = hud;
        this.sink = sink;
    }

    public void onRegisterKeys(RegisterKeyMappingsEvent e) {
        e.register(toggleHud);
        e.register(openStats);
        e.register(editHud);
    }

    public void tick(Minecraft mc) {
        while (toggleHud.consumeClick()) {
            config.hudEnabled = !config.hudEnabled;
            config.save();
        }
        while (openStats.consumeClick()) {
            mc.setScreen(new NeoStatScreen(config, aggregator, vault, sink, hud));
        }
        while (editHud.consumeClick()) {
            mc.setScreen(new NeoHudEditScreen(config, hud));
        }
    }
}
