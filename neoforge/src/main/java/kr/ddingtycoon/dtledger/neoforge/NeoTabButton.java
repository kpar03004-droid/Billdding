package kr.ddingtycoon.dtledger.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

/** 양피지 북마크 탭(tex_tab_active/inactive) — NeoForge 판(fabric TabButton 과 동일). */
public final class NeoTabButton extends AbstractButton {
    private final BooleanSupplier isActive;
    private final Runnable action;

    public NeoTabButton(int x, int y, int w, int h, Component message, BooleanSupplier isActive, Runnable action) {
        super(x, y, w, h, message);
        this.isActive = isActive;
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        boolean active = isActive.getAsBoolean();
        String tex = active ? "tex_tab_active" : (isHovered() ? "tex_tab_hover" : "tex_tab_inactive");
        NeoGuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = Minecraft.getInstance().font;
        int color = active ? NeoGuiTex.TITLE : 0xFFF0E2C2;
        int tw = font.width(getMessage());
        int ty = getY() + (getHeight() - 8) / 2 - 1; // 활성/비활성 공통 세로 중앙
        ctx.drawString(font, getMessage(), getX() + (getWidth() - tw) / 2, ty, color, false);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
        this.defaultButtonNarrationText(output);
    }
}
