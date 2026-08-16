package kr.ddingtycoon.dtledger.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

import java.util.function.BooleanSupplier;

/** 양피지 북마크 탭(tex_tab_active/inactive) — 활성 상태는 외부 상태값(BooleanSupplier)로 결정. */
public final class TabButton extends PressableWidget {
    private final BooleanSupplier isActive;
    private final Runnable action;

    public TabButton(int x, int y, int w, int h, Text message, BooleanSupplier isActive, Runnable action) {
        super(x, y, w, h, message);
        this.isActive = isActive;
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        boolean active = isActive.getAsBoolean();
        String tex = active ? "tex_tab_active" : (isHovered() ? "tex_tab_hover" : "tex_tab_inactive");
        GuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = MinecraftClient.getInstance().textRenderer;
        int color = active ? GuiTex.TITLE : 0xFFF0E2C2;
        int tw = font.getWidth(getMessage());
        int ty = getY() + (getHeight() - 8) / 2 - 1; // 활성/비활성 공통 세로 중앙
        ctx.drawText(font, getMessage(), getX() + (getWidth() - tw) / 2, ty, color, false);
    }

    @Override
    protected void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}
