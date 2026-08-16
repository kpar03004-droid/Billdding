package kr.ddingtycoon.dtledger.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 탭리스트 header/footer 텍스트를 read-only 로 노출. */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {
    @Accessor("header")
    Text getHeader();

    @Accessor("footer")
    Text getFooter();
}
