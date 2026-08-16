package kr.ddingtycoon.dtledger.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.text.Text;

/** 양피지 9-slice 버튼(tex_btn_base/hover/press) — Claude Design 텍스처. */
public final class TexButton extends PressableWidget {
    private final Runnable action;

    public TexButton(int x, int y, int w, int h, Text message, Runnable action) {
        super(x, y, w, h, message);
        this.action = action;
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        String tex = this.isHovered() ? "tex_btn_hover" : "tex_btn_base";
        GuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = MinecraftClient.getInstance().textRenderer;
        int tw = font.getWidth(getMessage());
        ctx.drawText(font, getMessage(), getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, GuiTex.BTN_TEXT, false);
    }

    @Override
    protected void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
        this.appendDefaultNarrations(builder);
    }
}
