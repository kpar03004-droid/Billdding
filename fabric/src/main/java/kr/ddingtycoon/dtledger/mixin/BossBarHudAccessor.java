package kr.ddingtycoon.dtledger.mixin;

import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.gui.hud.ClientBossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** 현재 표시 중인 보스바 맵을 read-only 로 노출. */
@Mixin(BossBarHud.class)
public interface BossBarHudAccessor {
    @Accessor("bossBars")
    Map<UUID, ClientBossBar> getBossBars();
}
