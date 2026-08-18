package kr.ddingtycoon.dtledger;

import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.CurrencyParser;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.QuestRewardTracker;
import kr.ddingtycoon.dtledger.core.SeaBlessingTracker;
import kr.ddingtycoon.dtledger.core.TransactionResolver;
import kr.ddingtycoon.dtledger.core.TransferClassifier;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.debug.BalanceProbe;
import kr.ddingtycoon.dtledger.store.LedgerStore;
import kr.ddingtycoon.dtledger.update.UpdateChecker;
import kr.ddingtycoon.dtledger.ui.DtKeyBindings;
import kr.ddingtycoon.dtledger.ui.DtStatCommand;
import kr.ddingtycoon.dtledger.ui.LedgerHud;
import kr.ddingtycoon.dtledger.watcher.BalanceWatcher;
import kr.ddingtycoon.dtledger.watcher.ChatWatcher;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.YearMonth;
import java.util.function.Consumer;

/**
 * 진입점. 감지(Watcher) → 파싱(Parser) → 결합(Resolver) → 분류(Classifier)
 * → 저장(Store)·집계(Aggregator) → 표시(HUD/Command) 파이프라인을 연결한다.
 *
 * 모든 구성요소는 read-only 관찰. 서버로 아무것도 전송하지 않는다.
 */
public final class DtLedgerClient implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("dtledger");

    /** GUI lore 스캔 주기(틱). 3 = 약 150ms 마다 → 매 틱 대비 호출 1/3, 지연은 사람이 못 느낌. */
    private static final int GUI_SCAN_INTERVAL = 3;

    @Override
    public void onInitializeClient() {
        java.nio.file.Path dir = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("billding");
        DtConfig config = DtConfig.load(dir);
        LedgerStore store = new LedgerStore(dir);
        DailyAggregator aggregator = new DailyAggregator(config, store);
        aggregator.ensureMonthLoaded(YearMonth.now()); // 당월 원장 복구

        TransferClassifier classifier = new TransferClassifier(config);
        VaultTracker vault = new VaultTracker(config.fleaVaultBalance, config.vaultLimit, v -> {
            config.fleaVaultBalance = v;
            config.save();
        });
        LedgerHud hud = new LedgerHud(config, aggregator, vault);

        Consumer<TransactionRecord> sink = rec -> {
            store.commit(rec);
            aggregator.addLive(rec);
            vault.onRecord(rec);
        };
        TransactionResolver resolver = new TransactionResolver(config, classifier, sink);
        // 금액을 못 알아낸 거래를 채팅으로 알린다 — 조용히 사라지면 유저가 알 방법이 없다.
        resolver.setNotifier(msg -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player == null) return;
                for (String line : msg.split("\n")) mc.player.sendMessage(Text.literal(line), false);
            });
        });

        // 채팅에 금액이 없는 GUI 거래들 — 창의 아이템 설명(lore)에서 금액을 읽어 확정한다.
        SeaBlessingTracker seaBlessing = new SeaBlessingTracker(sink); // 바다의 가호(지출)
        QuestRewardTracker questReward = new QuestRewardTracker(sink); // 일일/주간 의뢰(수입)

        CurrencyParser parser = CurrencyParser.createDefault();
        // ΔG 는 GUI 트래커들이 먼저 가져가고(정확한 금액을 아는 쪽 우선), 아니면 기존 Resolver 로.
        BalanceWatcher balanceWatcher = new BalanceWatcher(config, delta -> {
            if (seaBlessing.tryConsume(delta)) return;
            if (questReward.tryConsume(delta)) return;
            resolver.onDelta(delta);
        });
        ChatWatcher chatWatcher = new ChatWatcher(parser, resolver::onSignal,
                balanceWatcher::captureActionBar,
                // "/플리마켓 금고" 결과로 재동기화 + 잠수 중 놓친 거래를 차액으로 보정
                actual -> {
                    long missed = vault.syncFromServer(actual);
                    if (missed != 0 && config.vaultSyncAutoRecord) {
                        sink.accept(VaultTracker.missedRecord(missed, System.currentTimeMillis()));
                    }
                });

        chatWatcher.register();
        hud.register();
        new DtKeyBindings(config, aggregator, vault, hud, sink).register();
        new DtStatCommand(aggregator, config, store, vault, hud, sink).register();

        if (config.debugProbe) {
            new BalanceProbe().register();
            LOG.info("[dtledger] 진단 로거 ON — 잔고 소스 A/B 판정 후 config.debugProbe=false 로 끄세요.");
        }

        // 메인 틱 펌프: 강화창 상태 갱신 → 잔고 감지 → 결합 확정 → 저장 flush
        final int[] guiScanTick = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            long now = System.currentTimeMillis();

            // GUI lore 스캔(4종)은 컨테이너 창이 열려 있을 때만, 그리고 GUI_SCAN_INTERVAL 틱에 한 번만.
            // - 창이 닫혀 있으면 네 스캔 모두 즉시 빈 결과라 호출 자체가 낭비다.
            // - 열려 있어도 매 틱(20Hz) 전 슬롯 lore 를 훑을 필요는 없다. 강화 비용줄·단계 변화는
            //   수십 ms 안에만 잡으면 되므로 3틱(약 150ms)마다면 충분하다. → 호출 1/3.
            // ⚠️ "슬롯 내용이 같으면 스킵" 방식은 쓰지 않는다 — 강화 비용줄은 아이템이 안 바뀌어도
            //    호버 시 lore 에만 나타나므로, 그 방식은 강화·각인 비용을 놓친다(2026-08 실측).
            if (client.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen<?>
                    && ++guiScanTick[0] % GUI_SCAN_INTERVAL == 0) {
                seaBlessing.updateGui(kr.ddingtycoon.dtledger.watcher.GuiLoreScan.seaBlessing(client));
                // 이 서버는 잔고를 그림으로 그려 ΔG 를 못 읽는다 → 창의 강화 단계 상승으로 지출을 잡는다.
                seaBlessing.noteWindow(kr.ddingtycoon.dtledger.watcher.GuiLoreScan.seaBlessingAbilities(client));
                questReward.updateGui(kr.ddingtycoon.dtledger.watcher.GuiLoreScan.questEntries(client));
                kr.ddingtycoon.dtledger.watcher.GuiLoreScan.repairCosts(client).forEach(resolver::noteGuiCost);
                resolver.noteSkillCosts(kr.ddingtycoon.dtledger.watcher.GuiLoreScan.skillUpgradeCosts(client));
            }

            balanceWatcher.tick(client, now);   // 시간 기반 — 매 틱 유지
            resolver.tick(now);
            store.tick(now);
        });

        // 접속 시 잔고 기준선 리셋, 종료 시 저장 flush
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            balanceWatcher.reset();
            checkForUpdate(config, client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> store.flushNow());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> store.flushNow());

        LOG.info("[dtledger] Dt Ledger 초기화 완료 (환경: client, 서버 전송 없음).");
    }

    /**
     * 새 버전이 있으면 채팅에 한 줄 안내. 구버전으로 이미 고친 버그를 제보하는 일이 반복돼 추가.
     * 게임 서버와 무관한 GET 한 번이며 어떤 식별 정보도 보내지 않는다(설정에서 끌 수 있음).
     */
    private void checkForUpdate(kr.ddingtycoon.dtledger.config.DtConfig config, MinecraftClient client) {
        if (!config.updateCheckEnabled) return;
        String current = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("billding")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("0");

        UpdateChecker.checkAsync(config.updateCheckUrl, current, release -> client.execute(() -> {
            if (client.player == null) return;
            client.player.sendMessage(Text.literal("§6[빌띵] §f새 버전 §e" + release.version()
                    + "§f 이 나왔습니다. §7(현재 " + current + ")"), false);
            if (release.notes() != null && !release.notes().isBlank()) {
                client.player.sendMessage(Text.literal("§8· " + release.notes()), false);
            }
            String url = release.url();
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                client.player.sendMessage(Text.literal("§7다운로드: ")
                        .append(Text.literal("§b§n여기를 클릭")
                                .styled(st -> st
                                        .withClickEvent(new net.minecraft.text.ClickEvent(
                                                net.minecraft.text.ClickEvent.Action.OPEN_URL, url))
                                        .withHoverEvent(new net.minecraft.text.HoverEvent(
                                                net.minecraft.text.HoverEvent.Action.SHOW_TEXT, Text.literal(url))))),
                        false);
            }
        }));
    }

}
