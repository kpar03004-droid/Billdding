package kr.ddingtycoon.dtledger.neoforge;

import kr.ddingtycoon.dtledger.core.QuestRewardTracker;
import kr.ddingtycoon.dtledger.core.SeaBlessingTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

/**
 * 열린 컨테이너 화면의 아이템 설명(lore)에서 (금액 → 항목명)을 뽑는 공용 스캐너. (NeoForge 판)
 * 창 제목은 커스텀 리소스팩이라 신뢰 불가 → 화면에 보이는 아이템 설명으로 판별.
 * 읽기 전용, 서버 전송 없음.
 */
public final class NeoGuiLoreScan {
    private NeoGuiLoreScan() {}

    /** 바다의 가호 강화창 → (소모 골드 → 능력치명). */
    public static Map<Long, String> seaBlessing(Minecraft mc) {
        return scan(mc, SeaBlessingTracker.GUI_SIGNATURE,
                SeaBlessingTracker::parseCostGold, SeaBlessingTracker::abilityLabel);
    }

    /**
     * 의뢰 게시판(일일·주간 각각 별도 창) → 의뢰 목록(이름·보상액·수령여부).
     * 수령 여부까지 읽어야 "미수령 → 수령" 전환으로 수령 시점을 직접 잡을 수 있다.
     */
    public static java.util.List<QuestRewardTracker.Entry> questEntries(Minecraft mc) {
        if (mc == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return java.util.List.of();
        }
        java.util.List<QuestRewardTracker.Entry> out = new java.util.ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;

            String rawName = stack.getHoverName().getString();
            boolean isQuest = rawName.contains(QuestRewardTracker.GUI_SIGNATURE);
            long reward = 0;
            boolean claimed = false;
            for (Component line : lore.lines()) {
                String text = line.getString();
                if (text.contains(QuestRewardTracker.GUI_SIGNATURE)) isQuest = true;
                if (QuestRewardTracker.isClaimedLine(text)) claimed = true;
                long parsed = QuestRewardTracker.parseRewardGold(text);
                if (parsed > 0) reward = parsed;
            }
            if (!isQuest || reward <= 0) continue;
            out.add(new QuestRewardTracker.Entry(
                    QuestRewardTracker.questLabel(rawName), reward, claimed));
        }
        return out;
    }

    /**
     * 도구 수리 창에서 비용을 읽어 (유형 → 금액)으로 돌려준다.
     * 수리·품질회복은 채팅에 금액이 없고 매번 값이 달라, 창에 적힌 금액이 유일하게 정확한 근거다.
     */
    public static Map<kr.ddingtycoon.dtledger.core.TradeSignal.Type, Long> repairCosts(Minecraft mc) {
        if (mc == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return Map.of();
        }
        Map<kr.ddingtycoon.dtledger.core.TradeSignal.Type, Long> out = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;
            for (Component line : lore.lines()) {
                String text = line.getString();
                // 0 도 그대로 넘긴다 — "무료 수리"와 "못 읽음"을 Resolver 가 구분해야 한다.
                Long repair = kr.ddingtycoon.dtledger.core.RepairCostLore.parseRepairCost(text);
                if (repair != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.TOOL_REPAIR, repair);
                Long restore = kr.ddingtycoon.dtledger.core.RepairCostLore.parseRestoreCost(text);
                if (restore != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.QUALITY_RESTORE, restore);
                // 장비 강화 창(로니 → 도구 강화하기) — "강화 비용 : 700,000골드, 10루비"
                Long enhance = kr.ddingtycoon.dtledger.core.RepairCostLore.parseEnhanceCost(text);
                if (enhance != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.WEAPON_ENHANCE, enhance);
            }
        }
        return out;
    }

    private static Map<Long, String> scan(Minecraft mc, String signature,
                                          ToLongFunction<String> valueOf,
                                          UnaryOperator<String> nameOf) {
        if (mc == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return Map.of();
        }
        Map<Long, String> found = new HashMap<>();
        boolean matched = false;

        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;

            // 서명은 아이템 이름에도 있을 수 있음(의뢰 창은 이름이 "… 일일 의뢰")
            String rawName = stack.getHoverName().getString();
            if (rawName.contains(signature)) matched = true;

            long value = 0;
            for (Component line : lore.lines()) {
                String text = line.getString();
                if (text.contains(signature)) matched = true;
                long parsed = valueOf.applyAsLong(text);
                if (parsed > 0) value = parsed;
            }
            if (value <= 0) continue;

            String name = nameOf.apply(rawName);
            String prev = found.get(value);
            found.put(value, prev == null || prev.equals(name) ? name : "");
        }
        return matched ? found : Map.of();
    }

    /**
     * 전문가 스킬 창에서 (스킬 이름 → 업그레이드 비용)을 읽는다.
     *
     * <p>스킬 트리에는 노드가 여러 개 떠 있으므로 하나의 금액으로 뭉뚱그리지 않고
     * <b>스킬 이름별로</b> 담는다. 채팅이 "재배학개론 스킬 업그레이드에 성공했습니다"로
     * 오므로 이름으로 정확히 짝지을 수 있다.
     */
    public static Map<String, Long> skillUpgradeCosts(Minecraft mc) {
        if (mc == null || !(mc.screen instanceof AbstractContainerScreen<?> screen)) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore == null) continue;

            boolean isSkill = false;
            Long cost = null;
            for (Component line : lore.lines()) {
                String text = line.getString();
                if (kr.ddingtycoon.dtledger.core.SkillCostLore.isSignature(text)) isSkill = true;
                Long c = kr.ddingtycoon.dtledger.core.SkillCostLore.parseCost(text);
                if (c != null) cost = c;
            }
            // "소모 재화"는 바다의 가호 창에도 있다 → "소모 스킬 포인트"가 함께 있을 때만 인정
            if (!isSkill || cost == null) continue;
            String name = kr.ddingtycoon.dtledger.core.SkillCostLore.skillName(stack.getHoverName().getString());
            if (!name.isBlank()) out.put(name, cost);
        }
        return out;
    }

}
