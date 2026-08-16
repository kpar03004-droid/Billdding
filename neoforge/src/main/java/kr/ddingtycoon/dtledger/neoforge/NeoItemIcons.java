package kr.ddingtycoon.dtledger.neoforge;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.ddingtycoon.dtledger.core.CustomItemIcon;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산창 아이콘 렌더(NeoForge/mojmap). Fabric ItemIcons 와 동일 — 실제 품목 거래는 띵타 실물,
 * 카테고리 전용(품목 미매칭)은 Claude Design 창작 아이콘(NeoGuiTex).
 */
public final class NeoItemIcons {
    private NeoItemIcons() {}

    private static final Map<CustomItemIcon.Icon, ItemStack> CUSTOM = new HashMap<>();

    private static ItemStack custom(CustomItemIcon.Icon icon) {
        return CUSTOM.computeIfAbsent(icon, ic -> {
            ResourceLocation rl = ResourceLocation.tryParse(ic.baseItem());
            if (rl == null) return ItemStack.EMPTY;
            ItemStack s = new ItemStack(BuiltInRegistries.ITEM.getValue(rl));
            s.set(DataComponents.CUSTOM_MODEL_DATA,
                    new CustomModelData(List.of(ic.cmd()), List.of(), List.of(), List.of()));
            return s;
        });
    }

    /** 거래 1건(내역 탭): 품목 매칭 성공 → 띵타 실물. 실패 → 카테고리 창작 아이콘. */
    public static void drawRecord(GuiGraphics ctx, String label, String category, int x, int y, float scale) {
        CustomItemIcon.Icon i = CustomItemIcon.match(label);
        if (i != null) {
            drawStack(ctx, custom(i), x, y, scale);
        } else {
            drawCreative(ctx, category, x, y, scale);
        }
    }

    /** 카테고리 대표(오늘 탭 등): 항상 창작 아이콘. */
    public static void drawCategory(GuiGraphics ctx, String category, int x, int y, float scale) {
        drawCreative(ctx, category, x, y, scale);
    }

    private static void drawCreative(GuiGraphics ctx, String category, int x, int y, float scale) {
        int px = Math.round(16 * scale);
        NeoGuiTex.sprite(ctx, NeoGuiTex.icon(category), x, y, px, px);
    }

    private static void drawStack(GuiGraphics ctx, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) return;
        if (scale == 1.0f) {
            ctx.renderItem(stack, x, y);
            return;
        }
        PoseStack m = ctx.pose();
        m.pushPose();
        m.translate((float) x, (float) y, 0f);
        m.scale(scale, scale, 1f);
        ctx.renderItem(stack, 0, 0);
        m.popPose();
    }
}
