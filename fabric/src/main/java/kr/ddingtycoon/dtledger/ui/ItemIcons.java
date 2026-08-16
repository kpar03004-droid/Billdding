package kr.ddingtycoon.dtledger.ui;

import kr.ddingtycoon.dtledger.core.CustomItemIcon;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.CustomModelDataComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산창 아이콘 렌더 — 실제 품목 거래는 띵타 실물 아이콘, 카테고리 전용(품목 미매칭)은
 * Claude Design 창작 아이콘(GuiTex).
 *
 *   내역 탭(drawRecord): 품목별 매칭(CustomItemIcon.match) 성공 → 띵타 리소스팩 실물 텍스처.
 *                        실패(강화·각인·전문가·무역·유저상점·판매·플리마켓 등 카테고리성 거래) → 창작 아이콘.
 *   오늘 탭(drawCategory): 카테고리 집계라 항상 창작 아이콘.
 *
 * 띵타 아이콘은 리소스팩 로드 시에만 실제 텍스처로 보임(아트 미번들, 컴플라이언스).
 * 창작 아이콘은 모드에 직접 번들된 원본 PNG(항상 표시됨).
 */
public final class ItemIcons {
    private ItemIcons() {}

    private static final Map<CustomItemIcon.Icon, ItemStack> CUSTOM = new HashMap<>();

    private static ItemStack custom(CustomItemIcon.Icon icon) {
        return CUSTOM.computeIfAbsent(icon, ic -> {
            Identifier ident = Identifier.tryParse(ic.baseItem());
            if (ident == null) return ItemStack.EMPTY;
            ItemStack s = new ItemStack(Registries.ITEM.get(ident));
            s.set(DataComponentTypes.CUSTOM_MODEL_DATA,
                    new CustomModelDataComponent(List.of(ic.cmd()), List.of(), List.of(), List.of()));
            return s;
        });
    }

    /** 거래 1건(내역 탭): 품목 매칭 성공 → 띵타 실물. 실패 → 카테고리 창작 아이콘. */
    public static void drawRecord(DrawContext ctx, String label, String category, int x, int y, float scale) {
        CustomItemIcon.Icon i = CustomItemIcon.match(label);
        if (i != null) {
            drawStack(ctx, custom(i), x, y, scale);
        } else {
            drawCreative(ctx, category, x, y, scale);
        }
    }

    /** 카테고리 대표(오늘 탭 등): 항상 창작 아이콘. */
    public static void drawCategory(DrawContext ctx, String category, int x, int y, float scale) {
        drawCreative(ctx, category, x, y, scale);
    }

    private static void drawCreative(DrawContext ctx, String category, int x, int y, float scale) {
        int px = Math.round(16 * scale);
        GuiTex.sprite(ctx, GuiTex.icon(category), x, y, px, px);
    }

    private static void drawStack(DrawContext ctx, ItemStack stack, int x, int y, float scale) {
        if (stack.isEmpty()) return;
        if (scale == 1.0f) {
            ctx.drawItem(stack, x, y);
            return;
        }
        MatrixStack m = ctx.getMatrices();
        m.push();
        m.translate((float) x, (float) y, 0f);
        m.scale(scale, scale, 1f);
        ctx.drawItem(stack, 0, 0);
        m.pop();
    }
}
