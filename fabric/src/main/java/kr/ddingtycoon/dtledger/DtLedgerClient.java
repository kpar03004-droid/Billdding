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
        bindUpdater();
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
        final int[] updateTick = {0};
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

            // 새 버전 재확인 — 실제 네트워크 요청은 UpdateChecker 가 1시간 간격으로만 낸다.
            // 여기선 60초마다 두드려서, 구버전을 계속 쓰면 1시간마다 알림이 다시 뜨게 한다.
            if (++updateTick[0] % 1200 == 0) checkForUpdate(config, client);
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
    /**
     * 동의 기반 자동 설치기를 연결한다. 여기서는 <b>아무것도 받지 않는다</b> —
     * 실제 다운로드는 유저가 확인 화면에서 승인해야만 일어난다.
     *
     * <p>실패해도 조용히 넘어간다(개발 환경엔 신원 파일이 없고, mods 폴더가 아닌 데서
     * 로드될 수도 있다). 그 경우 기존의 "링크 눌러 직접 받기" 안내만 뜬다.
     */
    private void bindUpdater() {
        try {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            var mod = loader.getModContainer("billding")
                    .orElseThrow(() -> new java.io.IOException("모드 메타데이터를 찾을 수 없습니다"));
            // 칼띵과 같은 기준: 실제 로드된 경로가 '.jar 파일 하나'일 때만 자동 교체를 지원한다.
            //   (개발 환경은 클래스 폴더라 여기서 걸러진다)
            var jars = mod.getOrigin().getPaths().stream()
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName() != null && p.getFileName().toString().endsWith(".jar"))
                    .toList();
            if (jars.size() != 1) throw new java.io.IOException("실행 JAR 경로가 하나가 아니어서 자동 교체를 지원하지 않습니다");
            java.nio.file.Path mods = loader.getGameDir().resolve("mods").toAbsolutePath().normalize();
            if (!jars.get(0).toAbsolutePath().normalize().getParent().equals(mods))
                throw new java.io.IOException("mods 폴더에 직접 설치된 JAR가 아닙니다");
            kr.ddingtycoon.dtledger.update.UpdateInstaller.bind(
                    new kr.ddingtycoon.dtledger.update.ModUpdater(
                            "billding", mods, jars.get(0),
                            msg -> {
                                MinecraftClient mc = MinecraftClient.getInstance();
                                mc.execute(() -> {
                                    if (mc.player != null) mc.player.sendMessage(Text.literal("§6[빌띵] §r" + msg), false);
                                });
                            }));
        } catch (Exception e) {
            // 자동 설치 미지원 환경 — 알림은 수동 안내로만 뜨고, 업데이트 화면은 이 사유를 보여준다.
            kr.ddingtycoon.dtledger.update.UpdateInstaller.unavailable(
                    "현재 실행 파일을 확인할 수 없어 자동 업데이트할 수 없습니다: " + e.getMessage());
        }
    }

    private void checkForUpdate(kr.ddingtycoon.dtledger.config.DtConfig config, MinecraftClient client) {
        if (!config.updateCheckEnabled) return;
        String current = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("billding")
                .map(m -> m.getMetadata().getVersion().getFriendlyString())
                .orElse("0");

        UpdateChecker.checkAsync(config.updateCheckUrl, current, release -> client.execute(() -> {
            if (client.player == null) return;
            // 새 버전 알림이 떴을 때 릴리즈 정보를 미리 받아둔다 — 업데이트 화면을 열면 바로 보이게.
            //   ModUpdater 에 1시간 쿨다운이 내장돼 있어 GitHub 비인증 한도(시간당 60회)는 걱정 없다.
            var updater = kr.ddingtycoon.dtledger.update.UpdateInstaller.get();
            if (updater != null) updater.check();
            client.player.sendMessage(Text.literal("§6§m                                              "), false);
            client.player.sendMessage(Text.literal("§6§l 빌띵§r§f  새 버전 §a§l" + release.version()
                    + "§r §7(현재 " + current + ")"), false);
            if (release.notes() != null && !release.notes().isBlank()) {
                client.player.sendMessage(Text.literal("§7   " + release.notes()), false);
            }
            String url = release.url();
            if (url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                client.player.sendMessage(Text.literal("")
                        .append(Text.literal("§b§n » 다운로드 (여기 클릭)")
                                .styled(st -> st
                                        .withClickEvent(new net.minecraft.text.ClickEvent(
                                                net.minecraft.text.ClickEvent.Action.OPEN_URL, url))
                                        .withHoverEvent(new net.minecraft.text.HoverEvent(
                                                net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("§7" + url)))))
                        .append(Text.literal("§r§8   · 기존 파일 삭제 후 교체")),
                        false);
            }
            // 자동 설치가 가능한 환경이면 한 줄 더. 클릭은 '확인 화면'을 열 뿐,
            // 바로 받지 않는다 — 다운로드는 그 화면에서 동의해야 시작된다.
            if (kr.ddingtycoon.dtledger.update.UpdateInstaller.available()) {
                client.player.sendMessage(Text.literal("")
                        .append(Text.literal("§a§n » 모드가 대신 설치 (여기 클릭)")
                                .styled(st -> st
                                        .withClickEvent(new net.minecraft.text.ClickEvent(
                                                net.minecraft.text.ClickEvent.Action.RUN_COMMAND, "/빌띵 업데이트"))
                                        .withHoverEvent(new net.minecraft.text.HoverEvent(
                                                net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("§7확인 화면을 엽니다. 바로 받지 않습니다.")))))
                        .append(Text.literal("§r§8   · 동의 후 진행")),
                        false);
            }
        }));
    }

}
