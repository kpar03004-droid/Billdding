package kr.ddingtycoon.dtledger.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.aggregate.DailyBucket;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.export.LedgerExport;
import kr.ddingtycoon.dtledger.store.LedgerStore;
import kr.ddingtycoon.dtledger.util.GoldFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /빌띵 명령어 (NeoForge 판). 모든 출력은 로컬 피드백(서버 전송 없음).
 *
 * <p>한글이 기본, 영문이 별칭 — 이유는 Fabric 판 {@code DtStatCommand} 주석 참고.
 */
public final class NeoStatCommand {

    private final DailyAggregator aggregator;
    private final DtConfig config;
    private final LedgerStore store;
    private final VaultTracker vault;
    private final NeoLedgerHud hud;
    private final Path exportDir;
    private final java.util.function.Consumer<TransactionRecord> sink;

    public NeoStatCommand(DailyAggregator aggregator, DtConfig config, LedgerStore store,
                          VaultTracker vault, NeoLedgerHud hud, Path exportDir,
                          java.util.function.Consumer<TransactionRecord> sink) {
        this.aggregator = aggregator;
        this.config = config;
        this.store = store;
        this.vault = vault;
        this.hud = hud;
        this.exportDir = exportDir;
        this.sink = sink;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(tree("빌띵"));
        dispatcher.register(tree("dtstat"));   // 구버전 호환 별칭
    }

    /** 같은 하위 명령 묶음을 이름만 바꿔 두 번 만든다(빌띵 / dtstat). */
    private LiteralArgumentBuilder<CommandSourceStack> tree(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).executes(ctx -> openUi());
        both(root, "창", "ui", b -> b.executes(ctx -> openUi()));
        both(root, "오늘", "today", b -> b.executes(ctx -> printDay(ctx.getSource(), aggregator.today())));
        both(root, "주간", "week", b -> b.executes(ctx -> printWeek(ctx.getSource())));
        both(root, "날짜", "day", b -> b.then(Commands.argument("date", StringArgumentType.string())
                .executes(ctx -> printSpecificDay(ctx.getSource(), StringArgumentType.getString(ctx, "date")))));
        both(root, "미분류", "pending", b -> b.executes(ctx -> printPending(ctx.getSource())));
        both(root, "금고", "vault", b -> b.executes(ctx -> showVault(ctx.getSource()))
                .then(Commands.argument("amount", StringArgumentType.greedyString())
                        .executes(ctx -> setVault(ctx.getSource(), StringArgumentType.getString(ctx, "amount")))));
        both(root, "진단", "gui", b -> b.executes(ctx -> printDiagnostics(ctx.getSource())));
        both(root, "내보내기", "export", b -> b.executes(ctx -> exportCsv(ctx.getSource(), ""))
                .then(Commands.argument("범위", StringArgumentType.string())
                        .executes(ctx -> exportCsv(ctx.getSource(), StringArgumentType.getString(ctx, "범위")))));
        both(root, "표시", "hud", b -> b.executes(ctx -> toggleHud(ctx.getSource())));
        both(root, "위치", "move", b -> b.executes(ctx -> openHudEdit()));
        both(root, "초기화", "reset", b -> b.executes(ctx -> resetToday(ctx.getSource())));
        both(root, "추가", "add", b -> {
            both(b, "수입", "income", c -> c.then(Commands.argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> addManual(ctx.getSource(), TransactionRecord.Kind.INCOME,
                            StringArgumentType.getString(ctx, "args")))));
            both(b, "지출", "expense", c -> c.then(Commands.argument("args", StringArgumentType.greedyString())
                    .executes(ctx -> addManual(ctx.getSource(), TransactionRecord.Kind.EXPENSE,
                            StringArgumentType.getString(ctx, "args")))));
        });
        return root;
    }

    /** 한글 이름과 영문 이름을 같은 내용으로 나란히 등록한다. */
    private static void both(LiteralArgumentBuilder<CommandSourceStack> parent,
                             String ko, String en,
                             Consumer<LiteralArgumentBuilder<CommandSourceStack>> body) {
        for (String n : new String[]{ko, en}) {
            LiteralArgumentBuilder<CommandSourceStack> b = Commands.literal(n);
            body.accept(b);
            parent.then(b);
        }
    }

    /**
     * /빌띵 진단 — 지금 무엇을 읽고 있는지 출력. Fabric 판과 달리 창 스냅샷은 없고,
     * "고쳤는데 안 된다"의 원인 구분에 필요한 최소 정보(버전·잔고 읽기·ΔG·정산)만 낸다.
     */
    private int printDiagnostics(CommandSourceStack src) {
        String ver = net.neoforged.fml.loading.LoadingModList.get().getModFileById("billding") != null
                ? net.neoforged.fml.loading.LoadingModList.get().getModFileById("billding")
                        .getMods().get(0).getVersion().toString()
                : "?";
        send(src, "§6빌띵 버전: §f" + ver);

        String sig = kr.ddingtycoon.dtledger.core.TransactionResolver.lastSignalInfo();
        send(src, sig == null ? "§c최근 인식한 채팅 없음" : "§7최근 채팅 인식: §f" + sig);
        String costs = kr.ddingtycoon.dtledger.core.TransactionResolver.lastGuiCostInfo();
        send(src, costs == null || costs.isBlank()
                ? "§c창에서 읽은 비용 없음"
                : "§7창에서 읽은 비용: §f" + costs);
        String read = NeoBalanceWatcher.lastReadInfo();
        send(src, read == null ? "§c잔고를 아직 한 번도 못 읽음" : "§7잔고 읽기: §f" + read);
        String delta = kr.ddingtycoon.dtledger.core.TransactionResolver.lastDeltaInfo();
        send(src, delta == null ? "§c감지된 잔고 변동 없음" : "§7최근 잔고 변동: §f" + delta);
        String settle = kr.ddingtycoon.dtledger.core.TransactionResolver.lastSettleInfo();
        if (settle != null) send(src, "§7최근 처리 결과: §f" + settle);
        return 1;
    }

    private int openUi() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new NeoStatScreen(config, aggregator, vault, sink, hud)));
        return 1;
    }

    private int openHudEdit() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.setScreen(new NeoHudEditScreen(config, hud)));
        return 1;
    }

    private int printDay(CommandSourceStack src, DailyBucket b) {
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

    private void printCatMap(CommandSourceStack src, String header, Map<String, Long> map) {
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

    private int printWeek(CommandSourceStack src) {
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

    private int printSpecificDay(CommandSourceStack src, String dateStr) {
        try {
            LocalDate d = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
            return printDay(src, aggregator.day(d));
        } catch (Exception e) {
            send(src, "§c날짜 형식 오류. 예: /빌띵 날짜 2026-07-20");
            return 0;
        }
    }

    private int printPending(CommandSourceStack src) {
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

    private int showVault(CommandSourceStack src) {
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

    private int setVault(CommandSourceStack src, String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 11) {
            send(src, "§c금액 형식 오류. 예: /빌띵 금고 12,345,678");
            return 0;
        }
        vault.set(Long.parseLong(digits));
        send(src, "§a금고 잔액 설정: §f" + GoldFormat.format(vault.balance()) + " G §7(이후 자동 갱신)");
        return 1;
    }

    /**
     * @param scope "" (이번 달) · today · week · 2026-06 — 파일이 커지는 게 부담이면 좁혀 쓴다.
     */
    private int exportCsv(CommandSourceStack src, String scope) {
        try {
            LedgerExport.Scope sc = LedgerExport.resolve(store, config.dayResetHour, scope);
            if (sc.records().isEmpty()) {
                send(src, "§e해당 기간에 기록이 없습니다: §f" + sc.baseName());
                return 0;
            }
            String base = "빌띵-" + sc.baseName();
            Path xlsx = LedgerExport.writeXlsx(sc.records(), exportDir.resolve(base + ".xlsx"), config.dayResetHour);
            LedgerExport.writeCsv(sc.records(), exportDir.resolve(base + ".csv"), config.dayResetHour);
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

    private int resetToday(CommandSourceStack src) {
        int n = aggregator.resetToday();
        send(src, "§a오늘 데이터 초기화 완료 §7(" + n + "건 삭제) — 순익/수입/지출이 0부터 다시 집계됩니다.");
        return 1;
    }

    private int addManual(CommandSourceStack src, TransactionRecord.Kind kind, String args) {
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

    private int toggleHud(CommandSourceStack src) {
        config.hudEnabled = !config.hudEnabled;
        config.save();
        send(src, "§eHUD " + (config.hudEnabled ? "§a켜짐" : "§c꺼짐"));
        return 1;
    }

    private void send(CommandSourceStack src, String msg) {
        src.sendSuccess(() -> Component.literal(msg), false);
    }
}
