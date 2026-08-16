package kr.ddingtycoon.dtledger.neoforge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.aggregate.DailyBucket;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.util.GoldFormat;
import kr.ddingtycoon.dtledger.util.LedgerDates;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 양피지 가계부 HUD (NeoForge 판 — fabric LedgerHud 와 동일 디자인/레이아웃). */
public final class NeoLedgerHud implements LayeredDraw.Layer {

    private static final int PAD_X = 11, PAD_TOP = 9, PAD_BOT = 10;
    private static final int LINE = 11, GAP = 12, ICON = 11, MIN_W = 132, TOP_CATS = 4;

    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final VaultTracker vault;

    public NeoLedgerHud(DtConfig config, DailyAggregator aggregator, VaultTracker vault) {
        this.config = config;
        this.aggregator = aggregator;
        this.vault = vault;
    }

    @Override
    public void render(GuiGraphics ctx, DeltaTracker delta) {
        if (!config.hudEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        int[] pos = clampedPosition(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        drawScaledHud(ctx, mc.font, pos[0], pos[1]);
    }

    /**
     * config.hudScale 배율을 적용해 (x,y)에 그리고 {실제 너비, 실제 높이}(배율 반영) 반환.
     * 레이아웃은 배율 1 기준으로 계산하고 행렬로만 확대/축소.
     */
    public int[] drawScaledHud(GuiGraphics ctx, Font font, int x, int y) {
        float s = config.hudScaleClamped();
        if (s == 1.0f) return draw(ctx, font, x, y);
        PoseStack m = ctx.pose();
        m.pushPose();
        m.translate((float) x, (float) y, 0f);
        m.scale(s, s, 1f);
        int[] wh = draw(ctx, font, 0, 0);
        m.popPose();
        return new int[]{Math.round(wh[0] * s), Math.round(wh[1] * s)};
    }

    /** 배율까지 반영한 HUD 크기(그리지 않음) — 화면 클램프·편집 히트박스용. */
    public int[] measureScaled(Font font) {
        float s = config.hudScaleClamped();
        int[] wh = measure(font);
        return new int[]{Math.round(wh[0] * s), Math.round(wh[1] * s)};
    }

    private record Cat(String name, long amount, long max) {}

    private List<Cat> topCats(DailyBucket b) {
        List<Cat> out = new ArrayList<>();
        long max = 1;
        for (long v : b.incomeByCategory.values()) max = Math.max(max, v);
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(b.incomeByCategory.entrySet());
        sorted.sort((a, c) -> Long.compare(c.getValue(), a.getValue()));
        for (int i = 0; i < sorted.size() && out.size() < TOP_CATS; i++) {
            out.add(new Cat(sorted.get(i).getKey(), sorted.get(i).getValue(), max));
        }
        return out;
    }

    public int[] draw(GuiGraphics ctx, Font font, int x, int y) {
        DailyBucket b = aggregator.today();
        long net = b.netPnl();
        int netColor = net > 0 ? NeoGuiTex.GREEN : (net < 0 ? NeoGuiTex.RED : NeoGuiTex.NEUTRAL);

        LocalDate today = LedgerDates.today(config.dayResetHour);
        String title = "빌띵 · 오늘";
        String date = today.getMonthValue() + "/" + today.getDayOfMonth();
        String netVal = GoldFormat.signed(net) + " G";
        String inVal = GoldFormat.format(b.income) + " G";
        String outVal = GoldFormat.format(b.expense) + " G";
        boolean showTr = config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
        long trNet = b.transferIn - b.transferOut;
        String trVal = GoldFormat.signed(trNet) + " G";
        boolean showVault = config.hudShowVault && vault.isSet();
        List<Cat> cats = topCats(b);
        boolean showCats = !cats.isEmpty();

        float NET_SCALE = 1.35f;

        // 크기는 measure() 한 곳에서만 계산한다 — 예전엔 여기서 따로 계산하고 measure() 는
        // 너비를 154 로 고정 반환해, 금액 자릿수가 커지면 편집 화면 히트박스·크기조절이 어긋났다.
        int[] wh = measure(font);
        int w = wh[0];
        int h = wh[1];

        RenderSystem.setShaderColor(1f, 1f, 1f, config.hudOpacity);
        NeoGuiTex.sprite(ctx, "panel_light", x, y, w, h);

        int left = x + PAD_X;
        int right = x + w - PAD_X;
        int ty = y + PAD_TOP;

        ctx.drawString(font, title, left, ty, NeoGuiTex.TITLE, false);
        ctx.drawString(font, date, right - font.width(date), ty, NeoGuiTex.LABEL, false);
        ty += LINE;
        rule(ctx, left, right, ty);
        ty += 5;

        ctx.drawString(font, "순익", left, ty + (int) (font.lineHeight * (NET_SCALE - 1) / 2), NeoGuiTex.LABEL, false);
        drawScaled(ctx, font, netVal, right - (int) (font.width(netVal) * NET_SCALE), ty, netColor, NET_SCALE);
        ty += (int) (font.lineHeight * NET_SCALE) + 3;

        row(ctx, font, "수입", inVal, left, right, ty, NeoGuiTex.LABEL, NeoGuiTex.GREEN);
        ty += LINE;
        row(ctx, font, "지출", outVal, left, right, ty, NeoGuiTex.LABEL, NeoGuiTex.RED);
        ty += LINE;
        if (showTr) {
            row(ctx, font, "이체", trVal, left, right, ty, NeoGuiTex.LABEL, NeoGuiTex.BLUE);
            ty += LINE;
        }

        if (showCats) {
            ty += 1;
            rule(ctx, left, right, ty);
            ty += 5;
            for (Cat c : cats) {
                NeoGuiTex.sprite(ctx, NeoGuiTex.icon(c.name), left, ty - 1, ICON, ICON);
                int nameX = left + ICON + 4;
                ctx.drawString(font, c.name, nameX, ty, NeoGuiTex.TEXT, false);
                String amt = GoldFormat.format(c.amount);
                ctx.drawString(font, amt, right - font.width(amt), ty, NeoGuiTex.TEXT, false);
                int barY = ty + LINE - 1;
                int barW = right - nameX;
                ctx.fill(nameX, barY, right, barY + 2, NeoGuiTex.TRACK);
                int fill = (int) Math.max(2, (long) barW * c.amount / c.max);
                ctx.fill(nameX, barY, nameX + fill, barY + 2, NeoGuiTex.GOLD_BAR);
                ty += LINE + 4;
            }
        }

        if (showVault) {
            ty += 1;
            rule(ctx, left, right, ty);
            ty += 5;
            NeoGuiTex.sprite(ctx, "icon_coin", left, ty - 1, ICON, ICON);
            int nameX = left + ICON + 4;
            ctx.drawString(font, "금고", nameX, ty, NeoGuiTex.LABEL, false);
            String vbal = GoldFormat.format(vault.balance()) + " G";
            ctx.drawString(font, vbal, right - font.width(vbal), ty, NeoGuiTex.TEXT, false);
            int barY = ty + LINE - 1;
            double ratio = vault.fillRatio();
            int gc = ratio >= 0.9 ? NeoGuiTex.RED : NeoGuiTex.GOLD_BAR;
            ctx.fill(nameX, barY, right, barY + 2, NeoGuiTex.TRACK);
            ctx.fill(nameX, barY, nameX + (int) ((right - nameX) * Math.min(ratio, 1.0)), barY + 2, gc);
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        return new int[]{w, h};
    }

    public int[] measure(Font font) {
        DailyBucket b = aggregator.today();
        LocalDate today = LedgerDates.today(config.dayResetHour);
        String title = "빌띵 · 오늘";
        String date = today.getMonthValue() + "/" + today.getDayOfMonth();
        String netVal = GoldFormat.signed(b.netPnl()) + " G";
        String inVal = GoldFormat.format(b.income) + " G";
        String outVal = GoldFormat.format(b.expense) + " G";
        boolean showTr = config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
        String trVal = GoldFormat.signed(b.transferIn - b.transferOut) + " G";
        boolean showVault = config.hudShowVault && vault.isSet();
        List<Cat> cats = topCats(b);
        float NET_SCALE = 1.35f;

        // ── 너비 ──
        int w = font.width(title) + GAP + font.width(date);
        w = Math.max(w, font.width("순익") + GAP + (int) (font.width(netVal) * NET_SCALE));
        w = Math.max(w, font.width("수입") + GAP + font.width(inVal));
        w = Math.max(w, font.width("지출") + GAP + font.width(outVal));
        if (showTr) w = Math.max(w, font.width("이체") + GAP + font.width(trVal));
        for (Cat c : cats) {
            w = Math.max(w, ICON + 4 + font.width(c.name) + GAP + font.width(GoldFormat.format(c.amount)));
        }
        if (showVault) {
            w = Math.max(w, font.width("금고") + GAP + font.width(GoldFormat.format(vault.balance()) + " G"));
        }
        w = Math.max(w, MIN_W) + PAD_X * 2;

        // ── 높이 ──
        int catRowH = LINE + 4;
        int h = PAD_TOP + LINE + 5 + (int) (font.lineHeight * NET_SCALE) + 3 + LINE * 2
                + (showTr ? LINE : 0)
                + (!cats.isEmpty() ? 6 + cats.size() * catRowH : 0)
                + (showVault ? 6 + LINE + 4 : 0)
                + PAD_BOT;
        return new int[]{w, h};
    }

    /** 화면 밖으로 안 나가게 보정한 렌더 좌표(저장된 config.hudX/Y 는 건드리지 않음). */
    private int[] clampedPosition(int screenW, int screenH) {
        // 구버전 설정(절대 픽셀)을 현재 화면 기준 비율로 1회 승격 — Fabric 판과 동일.
        if (config.ensureHudRatio(screenW, screenH)) config.save();
        int[] wh = measureScaled(Minecraft.getInstance().font);
        int x = Math.max(0, Math.min(config.hudPixelX(screenW), Math.max(0, screenW - wh[0])));
        int y = Math.max(0, Math.min(config.hudPixelY(screenH), Math.max(0, screenH - wh[1])));
        return new int[]{x, y};
    }

    private static void rule(GuiGraphics ctx, int left, int right, int y) {
        ctx.fill(left, y, right, y + 1, NeoGuiTex.RULE);
    }

    private static void row(GuiGraphics ctx, Font font, String label, String value,
                            int left, int right, int y, int labelColor, int valueColor) {
        ctx.drawString(font, label, left, y, labelColor, false);
        ctx.drawString(font, value, right - font.width(value), y, valueColor, false);
    }

    private static void drawScaled(GuiGraphics ctx, Font font, String s, int x, int y, int color, float scale) {
        PoseStack m = ctx.pose();
        m.pushPose();
        m.translate((float) x, (float) y, 0f);
        m.scale(scale, scale, 1f);
        ctx.drawString(font, s, 0, 0, color, false);
        m.popPose();
    }
}
