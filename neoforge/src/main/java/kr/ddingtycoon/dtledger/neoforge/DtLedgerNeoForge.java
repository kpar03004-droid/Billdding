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

    /** GUI lore 스캔 주기(틱). Fabric 판과 동일 — 3틱(약 150ms)마다. */
    private static final int GUI_SCAN_INTERVAL = 3;
    private int guiScanTick = 0;
    private int updateTick = 0;

    public DtLedgerNeoForge(IEventBus modBus) {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("billding");
        DtConfig config = DtConfig.load(dir);
        bindUpdater();
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

            // GUI lore 스캔은 컨테이너 창이 열려 있을 때만, 3틱에 한 번만(Fabric 판과 동일 규칙).
            // 슬롯-해시 스킵은 쓰지 않는다 — 강화/각인 비용은 호버 시 lore 에만 떠서 놓친다.
            if (mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                    && ++guiScanTick % GUI_SCAN_INTERVAL == 0) {
                seaBlessing.updateGui(NeoGuiLoreScan.seaBlessing(mc));
                // 잔고를 못 읽는 서버 대비 — 창의 강화 단계 상승으로 바다의 가호 지출을 잡는다.
                seaBlessing.noteWindow(NeoGuiLoreScan.seaBlessingAbilities(mc));
                questReward.updateGui(NeoGuiLoreScan.questEntries(mc));
                NeoGuiLoreScan.repairCosts(mc).forEach(resolver::noteGuiCost);
                resolver.noteSkillCosts(NeoGuiLoreScan.skillUpgradeCosts(mc));
            }

            balanceWatcher.tick(mc, now);
            resolver.tick(now);
            store.tick(now);
            keys.tick(mc);
            if (probe != null) probe.tick(mc);

            // 새 버전 재확인 — 실제 요청은 UpdateChecker 가 1시간 간격으로만. 60초마다 두드린다.
            if (++updateTick % 1200 == 0) checkForUpdate(config);
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
                // 새 버전 알림이 떴을 때 릴리즈 정보를 미리 받아둔다 — 업데이트 화면을 열면 바로 보이게.
                //   ModUpdater 에 1시간 쿨다운이 내장돼 있어 GitHub 비인증 한도(시간당 60회)는 걱정 없다.
                var updater = kr.ddingtycoon.dtledger.update.UpdateInstaller.get();
                if (updater != null) updater.check();
                mc.player.displayClientMessage(Component.literal("§6§m                                              "), false);
                mc.player.displayClientMessage(Component.literal("§6§l 빌띵§r§f  새 버전 §a§l" + release.version()
                        + "§r §7(현재 " + current + ")"), false);
                if (release.notes() != null && !release.notes().isBlank()) {
                    mc.player.displayClientMessage(Component.literal("§7   " + release.notes()), false);
                }
                String url = release.url();
                if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                    mc.player.displayClientMessage(Component.literal("")
                            .append(Component.literal("§b§n » 다운로드 (여기 클릭)")
                                    .withStyle(st -> st
                                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                                    net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, url))
                                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                                    Component.literal("§7" + url)))))
                            .append(Component.literal("§r§8   · 기존 파일 삭제 후 교체")),
                            false);
                }
                // 자동 설치가 가능한 환경이면 한 줄 더. 클릭은 '확인 화면'을 열 뿐,
                // 바로 받지 않는다 — 다운로드는 그 화면에서 동의해야 시작된다.
                if (kr.ddingtycoon.dtledger.update.UpdateInstaller.available()) {
                    mc.player.displayClientMessage(Component.literal("")
                            .append(Component.literal("§a§n » 모드가 대신 설치 (여기 클릭)")
                                    .withStyle(st -> st
                                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                                    net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/빌띵 업데이트"))
                                            .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                                    net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                                    Component.literal("§7확인 화면을 엽니다. 바로 받지 않습니다.")))))
                            .append(Component.literal("§r§8   · 동의 후 진행")),
                            false);
                }
            });
        });
    }

    /**
     * 동의 기반 자동 설치기를 연결한다. 여기서는 <b>아무것도 받지 않는다</b> —
     * 실제 다운로드는 유저가 확인 화면에서 승인해야만 일어난다.
     *
     * <p>실패해도 조용히 넘어간다(개발 환경엔 신원 파일이 없고, mods 폴더가 아닌 데서
     * 로드될 수도 있다). 그 경우 기존의 "링크 눌러 직접 받기" 안내만 뜬다.
     */
    private static void bindUpdater() {
        try {
            var container = net.neoforged.fml.ModList.get().getModContainerById(MOD_ID)
                    .orElseThrow(() -> new java.io.IOException("모드 메타데이터를 찾을 수 없습니다"));
            Path jar = container.getModInfo().getOwningFile().getFile().getFilePath();
            // 칼띵과 같은 기준: 실제 로드된 경로가 '.jar 파일'일 때만 자동 교체를 지원한다.
            if (!java.nio.file.Files.isRegularFile(jar) || jar.getFileName() == null
                    || !jar.getFileName().toString().endsWith(".jar"))
                throw new java.io.IOException("개발 폴더/워크트리에서는 자동 교체를 지원하지 않습니다");
            jar = jar.toAbsolutePath().normalize();
            Path mods = FMLPaths.GAMEDIR.get().resolve("mods").toAbsolutePath().normalize();
            if (!jar.getParent().equals(mods))
                throw new java.io.IOException("mods 폴더에 직접 설치된 JAR가 아닙니다");
            kr.ddingtycoon.dtledger.update.UpdateInstaller.bind(
                    new kr.ddingtycoon.dtledger.update.ModUpdater(
                            MOD_ID, mods, jar,
                            msg -> {
                                Minecraft mc = Minecraft.getInstance();
                                mc.execute(() -> {
                                    if (mc.player != null) {
                                        mc.player.displayClientMessage(Component.literal("§6[빌띵] §r" + msg), false);
                                    }
                                });
                            }));
        } catch (Exception e) {
            // 자동 설치 미지원 환경 — 알림은 수동 안내로만 뜨고, 업데이트 화면은 이 사유를 보여준다.
            kr.ddingtycoon.dtledger.update.UpdateInstaller.unavailable(
                    "현재 실행 파일을 확인할 수 없어 자동 업데이트할 수 없습니다: " + e.getMessage());
        }
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
