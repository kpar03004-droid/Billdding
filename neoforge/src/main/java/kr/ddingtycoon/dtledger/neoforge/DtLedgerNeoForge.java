package kr.ddingtycoon.dtledger.neoforge;

import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.CurrencyParser;
import kr.ddingtycoon.dtledger.core.TradeSignal;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.QuestRewardTracker;
import kr.ddingtycoon.dtledger.core.SeaBlessingTracker;
import kr.ddingtycoon.dtledger.core.TransactionResolver;
import kr.ddingtycoon.dtledger.core.TransferClassifier;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.store.LedgerStore;
import kr.ddingtycoon.dtledger.update.UpdateChecker;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.YearMonth;
import java.util.function.Consumer;

/**
 * NeoForge 진입점. Fabric DtLedgerClient 와 동일한 파이프라인을
 * NeoForge 이벤트로 연결한다. 모든 구성요소 read-only, 서버 전송 없음.
 */
@Mod(value = DtLedgerNeoForge.MOD_ID, dist = Dist.CLIENT)
public final class DtLedgerNeoForge {
    public static final String MOD_ID = "billding";
    private static final Logger LOG = LoggerFactory.getLogger("billding");

    public DtLedgerNeoForge(IEventBus modBus) {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("billding");
        DtConfig config = DtConfig.load(dir);
        LedgerStore store = new LedgerStore(dir);
        DailyAggregator aggregator = new DailyAggregator(config, store);
        aggregator.ensureMonthLoaded(YearMonth.now());

        TransferClassifier classifier = new TransferClassifier(config);
        VaultTracker vault = new VaultTracker(config.fleaVaultBalance, config.vaultLimit, v -> {
            config.fleaVaultBalance = v;
            config.save();
        });

        Consumer<TransactionRecord> sink = rec -> {
            store.commit(rec);
            aggregator.addLive(rec);
            vault.onRecord(rec);
        };
        TransactionResolver resolver = new TransactionResolver(config, classifier, sink);
        // 금액을 못 알아낸 거래를 채팅으로 알린다 — 조용히 사라지면 유저가 알 방법이 없다.
        resolver.setNotifier(msg -> {
            Minecraft m = Minecraft.getInstance();
            m.execute(() -> {
                if (m.player == null) return;
                for (String line : msg.split("\n")) m.player.displayClientMessage(Component.literal(line), false);
            });
        });
        CurrencyParser parser = CurrencyParser.createDefault();

        // 채팅에 금액이 없는 GUI 거래들 — 창의 아이템 설명(lore)에서 금액을 읽어 확정한다.
        SeaBlessingTracker seaBlessing = new SeaBlessingTracker(sink); // 바다의 가호(지출)
        QuestRewardTracker questReward = new QuestRewardTracker(sink); // 일일/주간 의뢰(수입)
        NeoBalanceWatcher balanceWatcher = new NeoBalanceWatcher(config, delta -> {
            if (seaBlessing.tryConsume(delta)) return;
            if (questReward.tryConsume(delta)) return;
            resolver.onDelta(delta);
        });
        NeoLedgerHud hud = new NeoLedgerHud(config, aggregator, vault);
        NeoKeyBindings keys = new NeoKeyBindings(config, aggregator, vault, hud, sink);
        NeoBalanceProbe probe = config.debugProbe ? new NeoBalanceProbe(balanceWatcher) : null;

        // ── 모드 버스 ──
        modBus.addListener((RegisterGuiLayersEvent e) ->
                e.registerAboveAll(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ledger_hud"), hud));
        modBus.addListener(keys::onRegisterKeys);

        // ── 게임 버스 ──
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> {
            Minecraft mc = Minecraft.getInstance();
            long now = System.currentTimeMillis();
            seaBlessing.updateGui(NeoGuiLoreScan.seaBlessing(mc));
            questReward.updateGui(NeoGuiLoreScan.questEntries(mc));
            NeoGuiLoreScan.repairCosts(mc).forEach(resolver::noteGuiCost);
            resolver.noteSkillCosts(NeoGuiLoreScan.skillUpgradeCosts(mc));
            balanceWatcher.tick(mc, now);
            resolver.tick(now);
            store.tick(now);
            keys.tick(mc);
            if (probe != null) probe.tick(mc);
        });

        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.System e) -> {
            String s = e.getMessage().getString();
            if (e.isOverlay()) {
                balanceWatcher.captureActionBar(s);
                if (probe != null) probe.log("ACTIONBAR", s);
            } else {
                handleChat(parser, resolver, vault, config, sink, s);
                if (probe != null) probe.log("SYSTEM", s);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientChatReceivedEvent.Player e) -> {
            String s = e.getMessage().getString();
            handleChat(parser, resolver, vault, config, sink, s);
            if (probe != null) probe.log("CHAT", s);
        });

        NeoForge.EVENT_BUS.addListener((CustomizeGuiOverlayEvent.BossEventProgress e) ->
                balanceWatcher.captureBossBar(e.getBossEvent().getName().getString()));

        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            balanceWatcher.reset();
            checkForUpdate(config);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> store.flushNow());
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent e) -> store.flushNow());

        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) ->
                new NeoStatCommand(aggregator, config, store, vault, hud, dir, sink).register(e.getDispatcher()));

        if (probe != null) {
            LOG.info("[dtledger] 진단 로거 ON — 잔고 소스 A/B 판정 후 config.debugProbe=false 로 끄세요.");
        }
        LOG.info("[dtledger] Dt Ledger(NeoForge) 초기화 완료 (환경: client, 서버 전송 없음).");
    }

    /**
     * 새 버전이 있으면 채팅에 한 줄 안내(Fabric 판과 동일 동작).
     * 게임 서버와 무관한 GET 한 번이며 어떤 식별 정보도 보내지 않는다(설정에서 끌 수 있음).
     */
    private static void checkForUpdate(DtConfig config) {
        if (!config.updateCheckEnabled) return;
        String current = net.neoforged.fml.ModList.get().getModContainerById(MOD_ID)
                .map(m -> m.getModInfo().getVersion().toString())
                .orElse("0");

        UpdateChecker.checkAsync(config.updateCheckUrl, current, release -> {
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.player == null) return;
                mc.player.displayClientMessage(Component.literal("§6[빌띵] §f새 버전 §e" + release.version()
                        + "§f 이 나왔습니다. §7(현재 " + current + ")"), false);
                if (release.notes() != null && !release.notes().isBlank()) {
                    mc.player.displayClientMessage(Component.literal("§8· " + release.notes()), false);
                }
                String url = release.url();
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    mc.player.displayClientMessage(Component.literal("§7다운로드: ")
                            .append(Component.literal("§b§n여기를 클릭")
                                    .withStyle(st -> st
                                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                                    net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, url))
                                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(url))))),
                            false);
                }
            });
        });
    }

    private static void handleChat(CurrencyParser parser, TransactionResolver resolver,
                                   VaultTracker vault, DtConfig cfg,
                                   Consumer<TransactionRecord> recordSink, String message) {
        // 금고 잔액 스냅샷("/플리마켓 금고")이면 재동기화만 하고 거래 파싱은 건너뜀
        long vaultBalance = CurrencyParser.parseVaultBalance(message);
        if (vaultBalance >= 0) {
            long missed = vault.syncFromServer(vaultBalance);
            if (missed != 0 && cfg.vaultSyncAutoRecord) {
                recordSink.accept(VaultTracker.missedRecord(missed, System.currentTimeMillis()));
            }
            return;
        }
        TradeSignal sig = parser.parse(message);
        if (sig != null) {
            resolver.onSignal(sig);
        }
    }
}
