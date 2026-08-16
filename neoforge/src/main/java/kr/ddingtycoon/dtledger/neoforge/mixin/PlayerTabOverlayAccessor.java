package kr.ddingtycoon.dtledger.neoforge.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 탭리스트 header/footer 텍스트를 read-only 로 노출 (mojmap). */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
    @Accessor("header")
    Component getHeader();

    @Accessor("footer")
    Component getFooter();
}
