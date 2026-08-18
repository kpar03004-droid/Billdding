package kr.ddingtycoon.dtledger.watcher;

import kr.ddingtycoon.dtledger.core.QuestRewardTracker;
import kr.ddingtycoon.dtledger.core.SeaBlessingTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;

/**
 * 열린 컨테이너 화면의 아이템 설명(lore)에서 (금액 → 항목명)을 뽑는 공용 스캐너.
 *
 * 서버 커스텀 GUI 는 창 제목을 신뢰할 수 없어(리소스팩으로 대체됨) 화면에 이미 보이는
 * 아이템 설명으로 판별한다. 바다의 가호("소모 재화 : N골드")와 의뢰("보상 : N골드")가
 * 같은 구조라 한 곳에서 처리한다. 읽기 전용이며 서버로 아무것도 보내지 않는다.
 */
public final class GuiLoreScan {
    private GuiLoreScan() {}

    /**
     * 마지막으로 본 컨테이너 창에서 읽어낸 내용(진단용, /빌띵 진단).
     * GUI 가 열려 있으면 채팅을 칠 수 없어(창이 닫힘) 실시간 확인이 불가능하므로,
     * 창을 닫은 뒤 확인할 수 있게 스냅샷으로 남긴다. 읽기 전용이며 저장·전송하지 않는다.
     */
    private static volatile java.util.List<String> lastSnapshot = java.util.List.of();
    /** 마지막으로 본 의뢰 창의 의뢰별 수령 여부 — 수령 감지가 왜 안 됐는지 확인용. */
    private static volatile java.util.List<String> lastQuestSnapshot = java.util.List.of();

    public static java.util.List<String> lastSnapshot() {
        return lastSnapshot;
    }

    public static java.util.List<String> lastQuestSnapshot() {
        return lastQuestSnapshot;
    }

    /** 바다의 가호 강화창 → (소모 골드 → 능력치명). ΔG 폴백용(현재 이 서버는 ΔG 불가). */
    public static Map<Long, String> seaBlessing(MinecraftClient client) {
        return scan(client, SeaBlessingTracker.GUI_SIGNATURE,
                SeaBlessingTracker::parseCostGold, SeaBlessingTracker::abilityLabel);
    }

    /**
     * 바다의 가호 강화창 → 능력치별 (이름·현재 단계·다음 비용) 목록.
     * 단계 상승으로 강화 성공을 감지하려는 것 — 잔고를 못 읽는 서버에서 유일하게 확실한 신호.
     */
    public static java.util.List<SeaBlessingTracker.Ability> seaBlessingAbilities(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return java.util.List.of();
        }
        java.util.List<SeaBlessingTracker.Ability> out = new java.util.ArrayList<>();
        boolean matched = false;
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;
            String rawName = stack.getName().getString();
            if (rawName.contains(SeaBlessingTracker.GUI_SIGNATURE)) matched = true;
            long cost = 0;
            for (Text line : lore.lines()) {
                String t = line.getString();
                if (t.contains(SeaBlessingTracker.GUI_SIGNATURE)) matched = true;
                long c = SeaBlessingTracker.parseCostGold(t);
                if (c > 0) cost = c;
            }
            int level = SeaBlessingTracker.parseLevel(rawName);
            String label = SeaBlessingTracker.abilityLabel(rawName);
            if (cost > 0 && level >= 0 && !label.isEmpty()) {
                out.add(new SeaBlessingTracker.Ability(label, level, cost));
            }
        }
        return matched ? out : java.util.List.of();
    }

    /**
     * 의뢰 게시판(일일·주간 각각 별도 창) → 의뢰 목록(이름·보상액·수령여부).
     * 수령 여부까지 읽어야 "미수령 → 수령" 전환으로 수령 시점을 직접 잡을 수 있다.
     */
    public static java.util.List<QuestRewardTracker.Entry> questEntries(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return java.util.List.of();
        }
        java.util.List<QuestRewardTracker.Entry> out = new java.util.ArrayList<>();
        java.util.List<String> questSnapshot = new java.util.ArrayList<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;

            String rawName = stack.getName().getString();
            boolean isQuest = rawName.contains(QuestRewardTracker.GUI_SIGNATURE);
            long reward = 0;
            boolean claimed = false;
            for (Text line : lore.lines()) {
                String text = line.getString();
                if (text.contains(QuestRewardTracker.GUI_SIGNATURE)) isQuest = true;
                if (QuestRewardTracker.isClaimedLine(text)) claimed = true;
                long parsed = QuestRewardTracker.parseRewardGold(text);
                if (parsed > 0) reward = parsed;
            }
            if (!isQuest || reward <= 0) continue;
            String quest = QuestRewardTracker.questLabel(rawName);
            out.add(new QuestRewardTracker.Entry(quest, reward, claimed));
            questSnapshot.add("§8· §f" + quest + " §7보상 " + reward
                    + (claimed ? " §a[수령완료]" : " §e[미수령]"));
        }
        if (!out.isEmpty()) {
            questSnapshot.add(0, "§7의뢰 창 " + out.size() + "건 인식");
            lastQuestSnapshot = java.util.List.copyOf(questSnapshot);
        }
        return out;
    }

    /**
     * 도구 수리 창에서 비용을 읽어 (유형 → 금액)으로 돌려준다.
     * 수리·품질회복은 채팅에 금액이 없고 매번 값이 달라, 창에 적힌 금액이 유일하게 정확한 근거다.
     */
    public static Map<kr.ddingtycoon.dtledger.core.TradeSignal.Type, Long> repairCosts(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return Map.of();
        }
        Map<kr.ddingtycoon.dtledger.core.TradeSignal.Type, Long> out = new HashMap<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;
            for (Text line : lore.lines()) {
                String text = line.getString();
                // 0 도 그대로 넘긴다 — "무료 수리"와 "못 읽음"을 Resolver 가 구분해야 한다.
                Long repair = kr.ddingtycoon.dtledger.core.RepairCostLore.parseRepairCost(text);
                if (repair != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.TOOL_REPAIR, repair);
                Long restore = kr.ddingtycoon.dtledger.core.RepairCostLore.parseRestoreCost(text);
                if (restore != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.QUALITY_RESTORE, restore);
                // 장비 강화 창(로니 → 도구 강화하기) — "강화 비용 : 700,000골드, 10루비"
                Long enhance = kr.ddingtycoon.dtledger.core.RepairCostLore.parseEnhanceCost(text);
                if (enhance != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.WEAPON_ENHANCE, enhance);
                // 장비 각인 창 — "각인 비용 : 500,000골드, 3루비"(강화와 같은 형식)
                Long engrave = kr.ddingtycoon.dtledger.core.RepairCostLore.parseEngraveCost(text);
                if (engrave != null) out.put(kr.ddingtycoon.dtledger.core.TradeSignal.Type.ENGRAVE, engrave);
            }
        }
        // 진단 반영 — 이 경로로 읽은 비용을 '최근 창에서 읽은 내용'에 남긴다.
        // (그동안은 바다의 가호 스캔 결과만 떠서, 각인/강화 창인데 "서명 없음"으로 보였다.)
        if (!out.isEmpty()) {
            java.util.List<String> snap = new java.util.ArrayList<>();
            snap.add("§a수리/강화/각인 창 인식 — 읽은 비용:");
            out.forEach((type, cost) -> snap.add("§8· §f" + type + " §7→ §f" + cost + "골드"));
            snap.add("§7이 비용은 실제로 강화/각인을 해야 지출로 기록됩니다.");
            lastSnapshot = snap;
        }
        return out;
    }

    /**
     * @param signature 이 창임을 확인할 lore 문구(하나라도 있으면 해당 창으로 인정)
     * @param valueOf   lore 한 줄 → 금액(없으면 0)
     * @param nameOf    아이템 표시명 → 정리된 항목명
     * @return 금액 → 항목명. 해당 창이 아니면 빈 맵.
     */
    private static Map<Long, String> scan(MinecraftClient client, String signature,
                                          ToLongFunction<String> valueOf,
                                          UnaryOperator<String> nameOf) {
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return Map.of();
        }
        Map<Long, String> found = new HashMap<>();
        boolean matched = false;
        java.util.List<String> snap = new java.util.ArrayList<>();
        snap.add("§7창: §f" + screen.getTitle().getString() + " §7(서명 '" + signature + "' 검사)");

        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;

            // 서명은 아이템 이름에도 있을 수 있음(의뢰 창은 이름이 "… 일일 의뢰")
            String rawName = stack.getName().getString();
            if (rawName.contains(signature)) matched = true;

            long value = 0;
            for (Text line : lore.lines()) {
                String text = line.getString();
                if (text.contains(signature)) matched = true;
                long parsed = valueOf.applyAsLong(text);
                if (parsed > 0) value = parsed;
            }
            if (snap.size() < 30) {
                snap.add("§8· §f" + rawName + " §7→ 금액 " + (value > 0 ? value : "없음"));
            }
            if (value <= 0) continue;

            String name = nameOf.apply(rawName);
            String prev = found.get(value);
            // 같은 금액의 항목이 여러 개면 무엇인지 특정 불가 → 빈 이름(일반 라벨로 처리)
            found.put(value, prev == null || prev.equals(name) ? name : "");
        }
        snap.add(matched ? "§a서명 매칭됨 — 이 창으로 인식" : "§c서명 없음 — 이 창으로 인식 안 함");
        lastSnapshot = snap;
        return matched ? found : Map.of();
    }

    /**
     * 전문가 스킬 창에서 (스킬 이름 → 업그레이드 비용)을 읽는다.
     *
     * <p>스킬 트리에는 노드가 여러 개 떠 있으므로 하나의 금액으로 뭉뚱그리지 않고
     * <b>스킬 이름별로</b> 담는다. 채팅이 "재배학개론 스킬 업그레이드에 성공했습니다"로
     * 오므로 이름으로 정확히 짝지을 수 있다.
     */
    public static Map<String, Long> skillUpgradeCosts(MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof HandledScreen<?> screen)) {
            return Map.of();
        }
        Map<String, Long> out = new HashMap<>();
        for (Slot slot : screen.getScreenHandler().slots) {
            ItemStack stack = slot.getStack();
            if (stack.isEmpty()) continue;
            LoreComponent lore = stack.get(DataComponentTypes.LORE);
            if (lore == null) continue;

            boolean isSkill = false;
            Long cost = null;
            for (Text line : lore.lines()) {
                String text = line.getString();
                if (kr.ddingtycoon.dtledger.core.SkillCostLore.isSignature(text)) isSkill = true;
                Long c = kr.ddingtycoon.dtledger.core.SkillCostLore.parseCost(text);
                if (c != null) cost = c;
            }
            // "소모 재화"는 바다의 가호 창에도 있다 → "소모 스킬 포인트"가 함께 있을 때만 인정
            if (!isSkill || cost == null) continue;
            String name = kr.ddingtycoon.dtledger.core.SkillCostLore.skillName(stack.getName().getString());
            if (!name.isBlank()) out.put(name, cost);
        }
        return out;
    }

}
