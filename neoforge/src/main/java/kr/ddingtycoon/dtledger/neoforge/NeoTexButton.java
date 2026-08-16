package kr.ddingtycoon.dtledger.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

/** 양피지 9-slice 버튼(tex_btn_base/hover) — NeoForge 판(fabric TexButton 과 동일). */
public final class NeoTexButton extends AbstractButton {
    private final Runnable action;

    public NeoTexButton(int x, int y, int w, int h, Component message, Runnable action) {
        super(x, y, w, h, message);
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        String tex = this.isHovered() ? "tex_btn_hover" : "tex_btn_base";
        NeoGuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = Minecraft.getInstance().font;
        int tw = font.width(getMessage());
        ctx.drawString(font, getMessage(), getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, NeoGuiTex.BTN_TEXT, false);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
