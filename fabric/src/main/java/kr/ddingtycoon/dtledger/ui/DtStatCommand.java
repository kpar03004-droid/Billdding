package kr.ddingtycoon.dtledger.ui;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.aggregate.DailyBucket;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.config.DtConfigScreen;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.export.LedgerExport;
import kr.ddingtycoon.dtledger.store.LedgerStore;
import kr.ddingtycoon.dtledger.util.GoldFormat;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * /빌띵 명령어. 모든 출력은 로컬 채팅 피드백(서버 전송 없음).
 *
 * <p>모든 이름은 <b>한글이 기본, 영문이 별칭</b>이다. 영문을 남기는 이유는 두 가지 —
 * 기존 사용자의 손버릇과 공지·설명서가 그대로 살아 있어야 하고, 무엇보다
 * <b>마인크래프트 채팅창에서 한글 IME 가 안 먹는 환경</b>이 있어서 한글만 남기면
 * 그 사람들은 명령어를 아예 못 쓰게 된다.
 */
public final class DtStatCommand {

    private final DailyAggregator aggregator;
    private final DtConfig config;
    private final LedgerStore store;
    private final VaultTracker vault;
    private final LedgerHud hud;
    private final java.util.function.Consumer<TransactionRecord> sink;

    public DtStatCommand(DailyAggregator aggregator, DtConfig config, LedgerStore store,
                         VaultTracker vault, LedgerHud hud,
                         java.util.function.Consumer<TransactionRecord> sink) {
        this.aggregator = aggregator;
        this.config = config;
        this.store = store;
        this.vault = vault;
        this.hud = hud;
        this.sink = sink;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(tree("빌띵"));
            dispatcher.register(tree("dtstat"));   // 구버전 호환 별칭
        });
    }

    /** 같은 하위 명령 묶음을 이름만 바꿔 두 번 만든다(빌띵 / dtstat). */
    private LiteralArgumentBuilder<FabricClientCommandSource> tree(String name) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal(name).executes(ctx -> openUi());
        both(root, "창", "ui", b -> b.executes(ctx -> openUi()));
        both(root, "오늘", "today", b -> b.executes(ctx -> printDay(ctx.getSource(), aggregator.today())));
        both(root, "주간", "week", b -> b.executes(ctx -> printWeek(ctx.getSource())));
        both(root, "날짜", "day", b -> b.then(argument("date", StringArgumentType.string())
                .executes(ctx -> printSpecificDay(ctx.getSource(), StringArgumentType.getString(ctx, "date")))));
        both(root, "미분류", "pending", b -> b.executes(ctx -> printPending(ctx.getSource())));
        both(root, "금고", "vault", b -> b.executes(ctx -> showVault(ctx.getSource()))
                .then(argument("amount", StringArgumentType.greedyString())
                        .executes(ctx -> setVault(ctx.getSource(), StringArgumentType.getString(ctx, "amount")))));
        both(root, "진단", "gui", b -> b.executes(ctx -> printGuiSnapshot(ctx.getSource())));
        both(root, "내보내기", "export", b -> b.executes(ctx -> exportCsv(ctx.getSource(), ""))
                .then(argument("범위", StringArgumentType.string())
                        .executes(ctx -> exportCsv(ctx.getSource(), StringArgumentType.getString(ctx, "범위")))));
        both(root, "설정", "config", b -> b.executes(ctx -> openConfig(ctx.getSource())));
        both(root, "표시", "hud", b -> b.executes(ctx -> toggleHud(ctx.getSource())));
        both(root, "위치", "move", b -> b.executes(ctx -> openHudEdit()));
        both(root, "초기화", "reset", b -> b.executes(ctx -> resetToday(ctx.getSource())));
        both(root, "추가", "add", b -> {
            both(b, "수입", "income", c -> c.then(argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> addManual(ctx.getSource(), TransactionRecord.Kind.INCOME,
                            StringArgumentType.getString(ctx, "args")))));
            both(b, "지출", "expense", c -> c.then(argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> addManual(ctx.getSource(), TransactionRecord.Kind.EXPENSE,
                            StringArgumentType.getString(ctx, "args")))));
        });
        return root;
    }

    /** 한글 이름과 영문 이름을 같은 내용으로 나란히 등록한다. */
    private static void both(LiteralArgumentBuilder<FabricClientCommandSource> parent,
                             String ko, String en,
                             Consumer<LiteralArgumentBuilder<FabricClientCommandSource>> body) {
        for (String n : new String[]{ko, en}) {
            LiteralArgumentBuilder<FabricClientCommandSource> b = literal(n);
            body.accept(b);
            parent.then(b);
        }
    }

    private int printDay(FabricClientCommandSource src, DailyBucket b) {
        send(src, "§6=== 빌띵 · " + b.date + " ===");
        send(src, "§a수입 §f" + GoldFormat.format(b.income) + " G   §c지출 §f" + GoldFormat.format(b.expense) + " G");
        long net = b.netPnl();
        send(src, (net >= 0 ? "§a" : "§c") + "순익 " + GoldFormat.signed(net) + " G §7(거래 " + b.count + "건)");
        printCatMap(src, "§a[수입]", b.incomeByCategory);
        printCatMap(src, "§c[지출]", b.expenseByCategory);
        if (config.showTransfers && (b.transferIn > 0 || b.transferOut > 0)) {
            send(src, "§b[이체·참고] §7유입 " + GoldFormat.format(b.transferIn)
                    + " / 유출 " + GoldFormat.format(b.transferOut) + " §8(손익 제외)");
        }
        return 1;
    }

    private void printCatMap(FabricClientCommandSource src, String header, Map<String, Long> map) {
        if (map.isEmpty()) return;
        StringBuilder sb = new StringBuilder(header + " §f");
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) sb.append("§7, §f");
            sb.append(e.getKey()).append(" ").append(GoldFormat.format(e.getValue()));
            first = false;
        }
        send(src, sb.toString());
    }

    private int printWeek(FabricClientCommandSource src) {
        List<DailyBucket> days = aggregator.lastDays(7);
        send(src, "§6=== 빌띵 · 최근 7일 ===");
        long totIn = 0, totOut = 0, totNet = 0;
        for (DailyBucket b : days) {
            totIn += b.income;
            totOut += b.expense;
            totNet += b.netPnl();
            send(src, "§7" + b.date + " §a+" + GoldFormat.format(b.income)
                    + " §c-" + GoldFormat.format(b.expense)
                    + " §f= " + (b.netPnl() >= 0 ? "§a" : "§c") + GoldFormat.signed(b.netPnl()));
        }
        send(src, "§6합계 §a수입 " + GoldFormat.format(totIn) + " §c지출 " + GoldFormat.format(totOut)
                + " §f순익 " + (totNet >= 0 ? "§a" : "§c") + GoldFormat.signed(totNet));
        return 1;
    }

    private int printSpecificDay(FabricClientCommandSource src, String dateStr) {
        try {
            LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return printDay(src, aggregator.day(d));
        } catch (Exception e) {
            send(src, "§c날짜 형식 오류. 예: /빌띵 날짜 2026-07-20");
            return 0;
        }
    }

    private int printPending(FabricClientCommandSource src) {
        List<TransactionRecord> p = aggregator.pending();
        send(src, "§6=== 미분류(기타) " + p.size() + "건 ===");
        if (p.isEmpty()) {
            send(src, "§7없음 — 모든 거래가 분류되었습니다.");
        } else {
            for (TransactionRecord r : p) {
                send(src, "§7• " + r.kind + " " + GoldFormat.format(r.amount) + " §8" + r.note);
            }
        }
        return 1;
    }

    /**
     * 마지막으로 연 컨테이너 창에서 모드가 무엇을 읽었는지 출력(진단).
     * GUI 가 열려 있으면 채팅 입력이 불가능하므로, 창을 닫은 뒤 이 명령으로 확인한다.
     * 의뢰·바다의 가호처럼 창을 근거로 잡는 항목이 안 잡힐 때 원인을 바로 볼 수 있다.
     */
    private int printGuiSnapshot(FabricClientCommandSource src) {
        // 어느 빌드인지 먼저 — "고쳤는데 안 된다"의 상당수가 구버전 jar 였다
        String ver = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer("billding")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("?");
        send(src, "§6빌띵 버전: §f" + ver);

        // 채팅 규칙이 먹었는지 / 창에서 비용을 읽었는지 — 원인 구분용
        String sig = kr.ddingtycoon.dtledger.core.TransactionResolver.lastSignalInfo();
        send(src, sig == null ? "§c최근 인식한 채팅 없음" : "§7최근 채팅 인식: §f" + sig);
        String costs = kr.ddingtycoon.dtledger.core.TransactionResolver.lastGuiCostInfo();
        send(src, costs == null || costs.isBlank()
                ? "§c창에서 읽은 수리 비용 없음"
                : "§7읽은 수리 비용: §f" + costs);
        String read = kr.ddingtycoon.dtledger.watcher.BalanceWatcher.lastReadInfo();
        send(src, read == null ? "§c잔고를 아직 한 번도 못 읽음" : "§7잔고 읽기: §f" + read);
        String delta = kr.ddingtycoon.dtledger.core.TransactionResolver.lastDeltaInfo();
        send(src, delta == null ? "§c감지된 잔고 변동 없음" : "§7최근 잔고 변동: §f" + delta);
        String settle = kr.ddingtycoon.dtledger.core.TransactionResolver.lastSettleInfo();
        if (settle != null) send(src, "§7최근 처리 결과: §f" + settle);

        java.util.List<String> snap = kr.ddingtycoon.dtledger.watcher.GuiLoreScan.lastSnapshot();
        if (snap.isEmpty()) {
            send(src, "§7최근에 읽은 창이 없습니다. 의뢰/강화 창을 연 뒤 닫고 다시 실행하세요.");
            return 1;
        }
        send(src, "§6=== 최근 창에서 읽은 내용 ===");
        for (String line : snap) send(src, line);

        // 의뢰 창은 수령 여부까지 — 수령 감지가 왜 안 됐는지 바로 보인다
        java.util.List<String> quests = kr.ddingtycoon.dtledger.watcher.GuiLoreScan.lastQuestSnapshot();
        if (!quests.isEmpty()) {
            send(src, "§6--- 의뢰 상태 ---");
            for (String line : quests) send(src, line);
            String claim = kr.ddingtycoon.dtledger.core.QuestRewardTracker.lastClaimDetected();
            send(src, claim == null
                    ? "§c수령 전환 감지 이력 없음 (잔고 변동으로만 집계 중)"
                    : "§a마지막 수령 감지: §f" + claim);
        }
        return 1;
    }

    /**
     * @param scope "" (이번 달) · today · week · 2026-06 — 파일이 커지는 게 부담이면 좁혀 쓴다.
     */
    private int exportCsv(FabricClientCommandSource src, String scope) {
        try {
            LedgerExport.Scope sc = LedgerExport.resolve(store, config.dayResetHour, scope);
            if (sc.records().isEmpty()) {
                send(src, "§e해당 기간에 기록이 없습니다: §f" + sc.baseName());
                return 0;
            }
            Path dir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                    .resolve("billding"); // 모드 폴더와 일치(구 dtledger)
            String base = "빌띵-" + sc.baseName();
            Path xlsx = LedgerExport.writeXlsx(sc.records(), dir.resolve(base + ".xlsx"), config.dayResetHour);
            LedgerExport.writeCsv(sc.records(), dir.resolve(base + ".csv"), config.dayResetHour);
            send(src, "§a내보내기 완료(" + sc.records().size() + "건): §f" + xlsx);
            send(src, "§7시트: 거래내역 · 일별 · 주별 · 카테고리 §8(.csv 도 같은 폴더에)");
        } catch (IllegalArgumentException e) {
            send(src, "§c" + e.getMessage());
            send(src, "§7예: §f/빌띵 내보내기 week§7, §f/빌띵 내보내기 2026-06");
        } catch (Exception e) {
            send(src, "§c내보내기 실패: " + e.getMessage());
        }
        return 1;
    }

    /** 채팅창이 닫힌 다음 틱에 열어야 화면이 덮이지 않음 → mc.send 로 지연. */
    private int openUi() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new DtStatScreen(config, aggregator, vault, sink, hud)));
        return 1;
    }

    /** /빌띵 위치 — HUD 위치 편집 화면. */
    private int openHudEdit() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new DtHudEditScreen(config, hud)));
        return 1;
    }

    /** /빌띵 금고 — 금고 잔액 표시. */
    private int showVault(FabricClientCommandSource src) {
        if (!vault.isSet()) {
            send(src, "§e금고 미설정 — §f/빌띵 금고 <금액> §e또는 정산 창 금고 탭에서 입력하세요.");
        } else {
            int pct = (int) Math.round(vault.fillRatio() * 100);
            send(src, "§6금고 §f" + GoldFormat.format(vault.balance()) + " / "
                    + GoldFormat.format(vault.limit()) + " G §7(" + pct + "%)");
            if (vault.warning() != null) send(src, "§c⚠ " + vault.warning());
        }
        return 1;
    }

    /** /빌띵 금고 &lt;금액&gt; — 금고 잔액 설정·재동기화. */
    private int setVault(FabricClientCommandSource src, String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 11) {
            send(src, "§c금액 형식 오류. 예: /빌띵 금고 12,345,678");
            return 0;
        }
        vault.set(Long.parseLong(digits));
        send(src, "§a금고 잔액 설정: §f" + GoldFormat.format(vault.balance()) + " G §7(이후 자동 갱신)");
        return 1;
    }

    private int openConfig(FabricClientCommandSource src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> {
            net.minecraft.client.gui.screen.Screen screen = DtConfigScreen.create(config, null);
            if (screen != null) {
                mc.setScreen(screen);
            } else {
                src.sendFeedback(Text.literal("§cYACL 설정 화면을 열 수 없습니다. config/dtledger/config.json 을 직접 편집하세요."));
            }
        });
        return 1;
    }

    /** /빌띵 초기화 — 오늘 데이터 초기화. */
    private int resetToday(FabricClientCommandSource src) {
        int n = aggregator.resetToday();
        send(src, "§a오늘 데이터 초기화 완료 §7(" + n + "건 삭제) — 순익/수입/지출이 0부터 다시 집계됩니다.");
        return 1;
    }

    /** /빌띵 추가 수입|지출 &lt;금액&gt; [라벨] — 수동 기록 추가. */
    private int addManual(FabricClientCommandSource src, TransactionRecord.Kind kind, String args) {
        String[] parts = args.trim().split("\\s+", 2);
        String digits = parts[0].replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 15) {
            send(src, "§c형식: /빌띵 추가 " + (kind == TransactionRecord.Kind.INCOME ? "수입" : "지출") + " <금액> [설명]");
            return 0;
        }
        long amt = Long.parseLong(digits);
        String label = parts.length > 1 ? parts[1] : "수동 입력";
        TransactionRecord rec = new TransactionRecord(System.currentTimeMillis(), kind, amt,
                "수동", label, 0, true, TransactionRecord.Confidence.HIGH, false, "수동 입력");
        sink.accept(rec);
        send(src, (kind == TransactionRecord.Kind.INCOME ? "§a+수입 " : "§c-지출 ")
                + GoldFormat.format(amt) + " G §7[" + label + "] 추가됨");
        return 1;
    }

    private int toggleHud(FabricClientCommandSource src) {
        config.hudEnabled = !config.hudEnabled;
        config.save();
        send(src, "§eHUD " + (config.hudEnabled ? "§a켜짐" : "§c꺼짐"));
        return 1;
    }

    private void send(FabricClientCommandSource src, String msg) {
        src.sendFeedback(Text.literal(msg));
    }
}
