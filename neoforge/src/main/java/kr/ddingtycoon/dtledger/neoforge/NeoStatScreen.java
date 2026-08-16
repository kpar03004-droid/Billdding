package kr.ddingtycoon.dtledger.neoforge;

import kr.ddingtycoon.dtledger.aggregate.DailyAggregator;
import kr.ddingtycoon.dtledger.aggregate.DailyBucket;
import kr.ddingtycoon.dtledger.aggregate.RecordGrouping;
import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.VaultTracker;
import kr.ddingtycoon.dtledger.util.GoldFormat;
import kr.ddingtycoon.dtledger.util.LedgerDates;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /빌띵 정산 창(양피지 장부, NeoForge/mojmap 판) — Fabric DtStatScreen 과 동일 디자인/로직.
 * 배경/탭/버튼/구분선/스크롤바 전부 Claude Design 9-slice 텍스처(NeoGuiTex).
 */
public final class NeoStatScreen extends Screen {

    private static final int DIM = 0x90000000;

    private static final int W = 360;
    private static final int PAD = 12;
    private static final int ROW_H = 13;
    private static final int TOP = 36;
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private static final int TAB_STEP = 54;
    private static final int TAB_W = 52;
    private static final int TAB_H = 20;

    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final VaultTracker vault;
    private final NeoLedgerHud hud;
    private final java.util.function.Consumer<TransactionRecord> sink;
    private int tab = 0;

    private EditBox vaultInput;
    private NeoTexButton vaultApply;
    private NeoTexButton vaultResync;
    private boolean editingVault;

    private EditBox mAmount;
    private EditBox mLabel;
    private NeoTexButton mIncome;
    private NeoTexButton mExpense;
    private NeoTexButton mReset;
    private NeoTexButton mResetWeek;
    private NeoTexButton mResetDate;
    private EditBox mDate;
    /** 확인 대기 중인 초기화 버튼(0=없음 1=오늘 2=최근7일 3=지정날짜) — 오폭 방지 2단계 확인. */
    private int resetArmedKind;

    private NeoTexButton sHud;
    private NeoTexButton sOpacityDown;
    private NeoTexButton sOpacityUp;

    private static final int VISIBLE_PENDING = 12;
    private int pendingScroll;

    public NeoStatScreen(DtConfig config, DailyAggregator aggregator, VaultTracker vault,
                         java.util.function.Consumer<TransactionRecord> sink, NeoLedgerHud hud) {
        super(Component.literal("빌띵 정산"));
        this.config = config;
        this.aggregator = aggregator;
        this.vault = vault;
        this.sink = sink;
        this.hud = hud;
    }

    private int panelX() { return (this.width - W) / 2; }

    @Override
    protected void init() {
        int x = panelX();
        int tabY = TOP + 38; // 헤더 텍스트(TOP+24) 바로 아래. 페이지 상단 테두리(20px) 밖
        String[] names = {"오늘", "주간", "내역", "금고", "관리", "설정"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addRenderableWidget(new NeoTabButton(x + PAD + i * TAB_STEP, tabY, TAB_W, TAB_H, Component.literal(names[i]),
                    () -> this.tab == idx, () -> {
                this.tab = idx;
                resetArmedKind = 0; // 탭 이동 시 확인 대기 취소
                updateWidgets();
            }));
        }

        int cy = TOP + 62;

        vaultInput = new EditBox(this.font, x + PAD + 2, cy + 16, 146, 14, Component.literal("금고 잔액"));
        vaultInput.setMaxLength(11);
        vaultInput.setBordered(false);
        vaultInput.setTextColor(NeoGuiTex.TEXT);
        if (vault.isSet()) vaultInput.setValue(String.valueOf(vault.balance()));
        addRenderableWidget(vaultInput);

        vaultApply = new NeoTexButton(x + PAD + 156, cy + 13, 44, 18, Component.literal("설정"), this::applyVault);
        addRenderableWidget(vaultApply);

        vaultResync = new NeoTexButton(x + W - PAD - 64, cy, 64, 16, Component.literal("재동기화"), () -> {
            editingVault = true;
            vaultInput.setValue(String.valueOf(vault.balance()));
            updateWidgets();
        });
        addRenderableWidget(vaultResync);

        mAmount = new EditBox(this.font, x + PAD + 2, cy + 14, 106, 14, Component.literal("금액"));
        mAmount.setMaxLength(15);
        mAmount.setBordered(false);
        mAmount.setTextColor(NeoGuiTex.TEXT);
        addRenderableWidget(mAmount);

        mLabel = new EditBox(this.font, x + PAD + 118, cy + 14, W - PAD * 2 - 120, 14, Component.literal("설명"));
        mLabel.setMaxLength(40);
        mLabel.setBordered(false);
        mLabel.setTextColor(NeoGuiTex.TEXT);
        addRenderableWidget(mLabel);

        mIncome = new NeoTexButton(x + PAD, cy + 36, 150, 18, Component.literal("+ 수입 추가"), () -> addManual(true));
        addRenderableWidget(mIncome);

        mExpense = new NeoTexButton(x + W - PAD - 150, cy + 36, 150, 18, Component.literal("− 지출 추가"), () -> addManual(false));
        addRenderableWidget(mExpense);

        mReset = new NeoTexButton(x + PAD, cy + 68, (W - PAD * 2 - 4) / 2, 18,
                Component.literal("오늘 초기화"), this::onResetClick);
        addRenderableWidget(mReset);

        mResetWeek = new NeoTexButton(x + PAD + (W - PAD * 2 - 4) / 2 + 4, cy + 68, (W - PAD * 2 - 4) / 2, 18,
                Component.literal("최근 7일 초기화"), this::onResetWeekClick);
        addRenderableWidget(mResetWeek);

        // 특정 날짜만 지우기 — 잘못 기록된 날짜를 직접 지정(YYYY-MM-DD, 비우면 오늘)
        mDate = new EditBox(this.font, x + PAD + 2, cy + 104, 106, 14, Component.literal("날짜"));
        mDate.setMaxLength(10);
        mDate.setBordered(false);
        mDate.setTextColor(NeoGuiTex.TEXT);
        // placeholder 는 그림자로 글자가 두 겹으로 보여 쓰지 않음 — 형식은 라벨에 표기(2026-07-28).
        addRenderableWidget(mDate);

        mResetDate = new NeoTexButton(x + PAD + 116, cy + 102, W - PAD * 2 - 116, 18,
                Component.literal("이 날짜 초기화"), this::onResetDateClick);
        addRenderableWidget(mResetDate);

        sHud = new NeoTexButton(x + PAD, cy + 14, W - PAD * 2, 20, Component.literal("HUD 위치 설정"), () ->
                this.minecraft.setScreen(new NeoHudEditScreen(config, hud, this)));
        addRenderableWidget(sHud);

        sOpacityDown = new NeoTexButton(x + PAD, cy + 42, 30, 18, Component.literal("-"), () -> adjustOpacity(-0.1f));
        addRenderableWidget(sOpacityDown);
        sOpacityUp = new NeoTexButton(x + W - PAD - 30, cy + 42, 30, 18, Component.literal("+"), () -> adjustOpacity(0.1f));
        addRenderableWidget(sOpacityUp);

        updateWidgets();
    }

    private void updateWidgets() {
        boolean vaultEditor = tab == 3 && (!vault.isSet() || editingVault);
        vaultInput.visible = vaultEditor;
        vaultApply.visible = vaultEditor;
        vaultResync.visible = tab == 3 && vault.isSet() && !editingVault;

        boolean manage = tab == 4;
        mAmount.visible = manage;
        mLabel.visible = manage;
        mIncome.visible = manage;
        mExpense.visible = manage;
        mReset.visible = manage;
        if (mResetWeek != null) mResetWeek.visible = manage;
        if (mResetDate != null) mResetDate.visible = manage;
        if (mDate != null) mDate.visible = manage;
        if (mReset != null) mReset.setMessage(Component.literal(
                resetArmedKind == 1 ? "정말 지울까요?" : "오늘 초기화"));
        if (mResetWeek != null) mResetWeek.setMessage(Component.literal(
                resetArmedKind == 2 ? "정말 지울까요?" : "최근 7일 초기화"));
        if (mResetDate != null) mResetDate.setMessage(Component.literal(
                resetArmedKind == 3 ? "정말 지울까요?" : "이 날짜 초기화"));

        if (sHud != null) sHud.visible = tab == 5;
        if (sOpacityDown != null) sOpacityDown.visible = tab == 5;
        if (sOpacityUp != null) sOpacityUp.visible = tab == 5;
    }

    private void adjustOpacity(float delta) {
        config.hudOpacity = Math.round(Math.max(0.2f, Math.min(1.0f, config.hudOpacity + delta)) * 100) / 100f;
        config.save();
    }

    private void addManual(boolean income) {
        String digits = mAmount.getValue().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return;
        long amt;
        try {
            amt = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return;
        }
        String label = mLabel.getValue().isBlank() ? "수동 입력" : mLabel.getValue().trim();
        TransactionRecord rec = new TransactionRecord(System.currentTimeMillis(),
                income ? TransactionRecord.Kind.INCOME : TransactionRecord.Kind.EXPENSE,
                amt, "수동", label, 0, true, TransactionRecord.Confidence.HIGH, false, "수동 입력");
        sink.accept(rec);
        mAmount.setValue("");
        mLabel.setValue("");
    }

    private void onResetClick() {
        if (armOrConfirm(1)) aggregator.resetToday();
    }

    private void onResetWeekClick() {
        if (armOrConfirm(2)) aggregator.resetLastDays(7);
    }

    /** 지정 날짜(YYYY-MM-DD, 비우면 오늘)만 초기화 — 잘못 기록된 특정 날짜 수정용. */
    private void onResetDateClick() {
        java.time.LocalDate date = parseDateInput();
        if (date == null) {
            resetArmedKind = 0;
            updateWidgets();
            return;
        }
        if (armOrConfirm(3)) aggregator.resetDay(date);
    }

    private java.time.LocalDate parseDateInput() {
        String s = mDate == null ? "" : mDate.getValue().trim();
        if (s.isEmpty()) return LedgerDates.today(config.dayResetHour);
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 2단계 확인: 처음 누르면 그 버튼만 "정말 지울까요?"로 바뀌고 한 번 더 눌러야 실행.
     * 다른 초기화 버튼을 누르면 이전 대기는 취소(오폭 방지).
     */
    private boolean armOrConfirm(int kind) {
        if (resetArmedKind != kind) {
            resetArmedKind = kind;
            updateWidgets();
            return false;
        }
        resetArmedKind = 0;
        updateWidgets();
        return true;
    }

    /**
     * 내역 탭에서 Shift+클릭한 줄의 기록 삭제(묶음이면 원본 전부).
     * @return 지웠으면 true
     */
    private boolean deleteRowAt(double mouseX, double mouseY) {
        int x = panelX();
        int left = x + PAD, right = x + W - PAD;
        if (mouseX < left || mouseX > right) return false;

        List<RecordGrouping.Grouped> p = groupedPending();
        if (p.isEmpty()) return false;
        int total = p.size();
        int start = Math.max(0, Math.min(pendingScroll, Math.max(0, total - VISIBLE_PENDING)));
        int end = Math.min(total, start + VISIBLE_PENDING);

        int y0 = TOP + 62; // renderPending 시작 y 와 동일해야 함
        int row = (int) ((mouseY - y0) / ROW_H);
        if (row < 0 || row >= end - start) return false;

        RecordGrouping.Grouped g = p.get(total - 1 - (start + row)); // 최신이 위 — 렌더와 동일 순서
        boolean removed = false;
        for (TransactionRecord r : g.sources()) {
            if (aggregator.deleteRecord(r)) removed = true;
        }
        if (removed) pendingScroll = 0;
        return removed;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tab == 2 && button == 0 && hasShiftDown() && deleteRowAt(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void applyVault() {
        String digits = vaultInput.getValue().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return;
        long v;
        try {
            v = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return;
        }
        vault.set(v);
        editingVault = false;
        updateWidgets();
    }

    @Override
    public void renderBackground(GuiGraphics ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, DIM);
        drawPanel(ctx);
    }

    private void drawPanel(GuiGraphics ctx) {
        int x = panelX();
        int h = 68 + contentHeight() + PAD; // cy(TOP+62) + 6
        int right = x + W;

        NeoGuiTex.sprite(ctx, "tex_page", x, TOP, W, h);

        ctx.drawString(font, "빌띵", x + PAD, TOP + 24, NeoGuiTex.TITLE, false);
        String today = LedgerDates.today(config.dayResetHour).toString();
        ctx.drawString(font, today, right - PAD - font.width(today), TOP + 24, NeoGuiTex.LABEL, false);

        int cy = TOP + 62;
        switch (tab) {
            case 0 -> renderToday(ctx, x, cy);
            case 1 -> renderWeek(ctx, x, cy);
            case 2 -> renderPending(ctx, x, cy);
            case 3 -> renderVault(ctx, x, cy);
            case 4 -> renderManage(ctx, x, cy);
            case 5 -> renderSettings(ctx, x, cy);
        }
    }

    private int contentHeight() {
        return switch (tab) {
            case 0 -> {
                DailyBucket b = aggregator.today();
                int cats = shownCats(b.incomeByCategory) + shownCats(b.expenseByCategory);
                yield 64 + (cats > 0 ? 16 + cats * ROW_H : 0) + (showTransfers(b) ? 16 : 0);
            }
            case 1 -> 7 * (ROW_H + 1) + 26 + ROW_H; // +범례 한 줄
            case 2 -> Math.max(28, Math.min(Math.max(groupedPending().size(), 1), VISIBLE_PENDING) * ROW_H + 16);
            case 4 -> 140; // 수동입력 + 초기화 3종(날짜칸·안내문 포함) — 패널 밖 넘침 방지
            case 5 -> 92;
            default -> (!vault.isSet() || editingVault) ? 56 : 128;
        };
    }

    private static int shownCats(Map<String, Long> m) { return Math.min(m.size(), 5); }

    private boolean showTransfers(DailyBucket b) {
        return config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
    }

    private void renderToday(GuiGraphics ctx, int x, int y) {
        DailyBucket b = aggregator.today();
        long net = b.netPnl();
        int left = x + PAD, right = x + W - PAD;

        NeoGuiTex.sprite(ctx, "tex_card", left, y, right - left, 30);
        ctx.drawString(font, "오늘 순익", left + 8, y + 5, NeoGuiTex.LABEL, false);
        String netStr = GoldFormat.signed(net) + " G";
        int netColor = net > 0 ? NeoGuiTex.GREEN : (net < 0 ? NeoGuiTex.RED : NeoGuiTex.NEUTRAL);
        ctx.drawString(font, netStr, left + 8, y + 16, netColor, false);
        String cnt = b.count + "건";
        ctx.drawString(font, cnt, right - 8 - font.width(cnt), y + 16, NeoGuiTex.LABEL, false);
        y += 36;

        int half = (right - left - 8) / 2;
        ctx.drawString(font, "▲ 수입", left, y, NeoGuiTex.GREEN, false);
        String in = GoldFormat.format(b.income);
        ctx.drawString(font, in, left + half - font.width(in), y, NeoGuiTex.TEXT, false);
        ctx.drawString(font, "▼ 지출", left + half + 8, y, NeoGuiTex.RED, false);
        String out = GoldFormat.format(b.expense);
        ctx.drawString(font, out, right - font.width(out), y, NeoGuiTex.TEXT, false);
        y += 16;

        int cats = shownCats(b.incomeByCategory) + shownCats(b.expenseByCategory);
        if (cats > 0) {
            NeoGuiTex.tileH(ctx, "tex_divider", left, right, y, 48, 6);
            y += 6;
            long max = 1;
            for (long v : b.incomeByCategory.values()) max = Math.max(max, v);
            for (long v : b.expenseByCategory.values()) max = Math.max(max, v);
            y = catRows(ctx, b.incomeByCategory, left, right, y, max, NeoGuiTex.GREEN);
            y = catRows(ctx, b.expenseByCategory, left, right, y, max, NeoGuiTex.RED);
        }

        if (showTransfers(b)) {
            y += 4;
            String tr = "이체(손익 제외)  +" + GoldFormat.format(b.transferIn) + " / -" + GoldFormat.format(b.transferOut);
            ctx.drawString(font, tr, left, y, NeoGuiTex.BLUE, false);
        }
    }

    private int catRows(GuiGraphics ctx, Map<String, Long> map, int left, int right, int y,
                        long max, int accent) {
        int shown = 0;
        for (Map.Entry<String, Long> e : sortDesc(map).entrySet()) {
            if (shown++ >= 5) break;
            NeoItemIcons.drawCategory(ctx, e.getKey(), left, y, 0.75f); // 12px 창작 아이콘(카테고리 대표)
            int labelLeft = left + 15;
            String label = font.plainSubstrByWidth(e.getKey(), 60);
            ctx.drawString(font, label, labelLeft, y + 2, NeoGuiTex.LABEL, false);
            int barLeft = left + 80;
            int barMax = right - barLeft - 76;
            int bw = (int) Math.max(2, barMax * e.getValue() / max);
            ctx.fill(barLeft, y + 3, barLeft + barMax, y + 9, NeoGuiTex.TRACK);
            ctx.fill(barLeft, y + 3, barLeft + bw, y + 9, accent);
            String amt = GoldFormat.format(e.getValue());
            ctx.drawString(font, amt, right - font.width(amt), y + 2, NeoGuiTex.TEXT, false);
            y += ROW_H;
        }
        return y;
    }

    private static Map<String, Long> sortDesc(Map<String, Long> m) {
        Map<String, Long> out = new LinkedHashMap<>();
        m.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private void renderWeek(GuiGraphics ctx, int x, int y) {
        List<DailyBucket> days = aggregator.lastDays(7);
        int left = x + PAD, right = x + W - PAD;
        // 수입·지출을 같이 보여준다(2026-07-28 요청) — 축 기준 오른쪽=수입, 왼쪽=지출.
        long maxAbs = 1, totIn = 0, totOut = 0;
        for (DailyBucket d : days) {
            maxAbs = Math.max(maxAbs, Math.max(d.income, d.expense));
            totIn += d.income;
            totOut += d.expense;
        }

        int cx = left + 120;
        int barMax = 70;
        for (DailyBucket d : days) {
            long net = d.netPnl();
            ctx.drawString(font, d.date.format(DAY_FMT), left, y + 2, NeoGuiTex.LABEL, false);
            ctx.fill(cx, y + 1, cx + 1, y + 11, NeoGuiTex.RULE);
            if (d.income > 0) {
                int bw = Math.max((int) (barMax * d.income / maxAbs), 1);
                ctx.fill(cx + 1, y + 2, cx + 1 + bw, y + 6, NeoGuiTex.GREEN);   // 위: 수입
            }
            if (d.expense > 0) {
                int bw = Math.max((int) (barMax * d.expense / maxAbs), 1);
                ctx.fill(cx - bw, y + 6, cx, y + 10, NeoGuiTex.RED);            // 아래: 지출
            }
            // 오른쪽: 순익(맨 끝) + 그날 지출(빨강, 바로 왼쪽) — 빨간 막대가 얼마인지 숫자로도 표시
            String v = GoldFormat.signed(net);
            int vc = net > 0 ? NeoGuiTex.GREEN : (net < 0 ? NeoGuiTex.RED : NeoGuiTex.NEUTRAL);
            ctx.drawString(font, v, right - font.width(v), y + 2, vc, false);
            if (d.expense > 0) {
                String ex = "-" + GoldFormat.format(d.expense);
                int exX = right - font.width(v) - 8 - font.width(ex);
                ctx.drawString(font, ex, exX, y + 2, NeoGuiTex.RED, false);
            }
            y += ROW_H + 1;
        }

        // 범례
        ctx.fill(cx + 1, y + 3, cx + 9, y + 7, NeoGuiTex.GREEN);
        ctx.drawString(font, "수입", cx + 12, y + 1, NeoGuiTex.LABEL, false);
        ctx.fill(cx + 40, y + 3, cx + 48, y + 7, NeoGuiTex.RED);
        ctx.drawString(font, "지출", cx + 51, y + 1, NeoGuiTex.LABEL, false);
        y += ROW_H;

        y += 4;
        NeoGuiTex.tileH(ctx, "tex_divider", left, right, y, 48, 6);
        y += 5;
        long totNet = totIn - totOut;
        ctx.drawString(font, "7일 합계", left, y, NeoGuiTex.LABEL, false);
        String sum = "+" + GoldFormat.format(totIn) + " / -" + GoldFormat.format(totOut)
                + "  =  " + GoldFormat.signed(totNet);
        int sc = totNet >= 0 ? NeoGuiTex.GREEN : NeoGuiTex.RED;
        ctx.drawString(font, sum, right - font.width(sum), y, sc, false);
    }

    /** 표시용 캐시 — 스크롤·렌더 양쪽에서 같은 그룹핑 결과를 쓰도록 한 프레임 동안 재사용. */
    private List<RecordGrouping.Grouped> groupedPending() {
        return RecordGrouping.collapseConsecutive(aggregator.recent());
    }

    private void renderPending(GuiGraphics ctx, int x, int y) {
        List<RecordGrouping.Grouped> p = groupedPending(); // 연속 동일거래 묶음("×N건") — 도배 방지
        int left = x + PAD, right = x + W - PAD;
        if (p.isEmpty()) {
            ctx.drawString(font, "오늘 거래 내역이 없습니다.", left, y + 4, NeoGuiTex.LABEL, false);
            return;
        }
        int total = p.size();
        int maxScroll = Math.max(0, total - VISIBLE_PENDING);
        pendingScroll = Math.max(0, Math.min(pendingScroll, maxScroll));
        int start = pendingScroll;
        int end = Math.min(total, start + VISIBLE_PENDING);

        int y0 = y;
        boolean scrollable = total > VISIBLE_PENDING;
        int rowRight = scrollable ? right - 10 : right;
        for (int i = start; i < end; i++) {
            RecordGrouping.Grouped r = p.get(total - 1 - i);
            boolean plus = r.kind() == TransactionRecord.Kind.INCOME || r.kind() == TransactionRecord.Kind.TRANSFER_IN;
            int color = switch (r.kind()) {
                case INCOME -> NeoGuiTex.GREEN;
                case EXPENSE -> NeoGuiTex.RED;
                default -> NeoGuiTex.BLUE;
            };
            NeoItemIcons.drawRecord(ctx, r.label(), r.category(), left, y, 0.75f); // 12px 아이콘(품목 매칭→띵타 / 그 외→창작)
            int textLeft = left + 15;
            String amt = (plus ? "+" : "-") + GoldFormat.format(r.amount())
                    + (r.qty() > 0 ? " (" + r.qty() + "개)" : "");
            ctx.drawString(font, amt, textLeft, y + 2, color, false);
            int amtW = font.width(amt);
            String cat = r.category() == null ? "" : r.category();
            String lbl = r.label() == null || r.label().isEmpty() || r.label().equals(cat) ? "" : "  " + r.label();
            String mult = r.count() > 1 ? "  ×" + r.count() : "";
            String catLine = font.plainSubstrByWidth(cat + lbl + mult, rowRight - textLeft - amtW - 8);
            ctx.drawString(font, catLine, rowRight - font.width(catLine), y + 2, NeoGuiTex.LABEL, false);
            y += ROW_H;
        }

        if (scrollable) {
            int trackTop = y0, trackH = VISIBLE_PENDING * ROW_H;
            int barX = right - 8;
            NeoGuiTex.tileV(ctx, "tex_scroll_track", barX, trackTop, trackTop + trackH, 8, 16);
            int thumbH = Math.max(16, trackH * VISIBLE_PENDING / total);
            int thumbY = trackTop + (int) ((long) (trackH - thumbH) * start / maxScroll);
            NeoGuiTex.sprite(ctx, "tex_scroll_thumb", barX, thumbY, 8, thumbH);
            String pos = (start + 1) + "–" + end + " / " + total + "  · 휠 스크롤";
            ctx.drawString(font, pos, left, y + 2, NeoGuiTex.LABEL, false);
        }
        // 잘못 들어간 기록만 지우는 방법 안내(2026-07-28)
        String tip = "Shift+클릭 = 그 줄 삭제";
        ctx.drawString(font, tip, right - font.width(tip), y + 2, NeoGuiTex.LABEL, false);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        if (tab == 2) {
            pendingScroll -= (int) Math.signum(vert);
            int maxScroll = Math.max(0, groupedPending().size() - VISIBLE_PENDING);
            pendingScroll = Math.max(0, Math.min(pendingScroll, maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, horiz, vert);
    }

    private void renderVault(GuiGraphics ctx, int x, int y) {
        int left = x + PAD, right = x + W - PAD;

        if (!vault.isSet() || editingVault) {
            ctx.drawString(font,
                    "플리마켓 금고 잔액 입력 (한도 " + GoldFormat.format(vault.limit()) + " G)",
                    left, y, NeoGuiTex.LABEL, false);
            NeoGuiTex.sprite(ctx, "tex_input", x + PAD, y + 14, 150, 18);
            ctx.drawString(font, "이후 플리 판매·구매·입출금으로 자동 갱신됩니다.",
                    left, y + 36, NeoGuiTex.LABEL, false);
            return;
        }

        ctx.drawString(font, "플리마켓 금고", left, y, NeoGuiTex.LABEL, false);
        y += 12;
        String bal = GoldFormat.format(vault.balance()) + " G";
        ctx.drawString(font, bal, left, y, NeoGuiTex.GOLD, false);
        String lim = "/ " + GoldFormat.format(vault.limit());
        ctx.drawString(font, lim, left + font.width(bal) + 6, y, NeoGuiTex.LABEL, false);
        y += 14;

        double ratio = vault.fillRatio();
        int fillColor = ratio < 0.7 ? NeoGuiTex.GREEN : (ratio < 0.9 ? NeoGuiTex.GOLD_BAR : NeoGuiTex.RED);
        int barW = right - left - 40;
        ctx.fill(left, y, left + barW, y + 8, NeoGuiTex.TRACK);
        ctx.fill(left, y, left + (int) (barW * Math.min(ratio, 1.0)), y + 8, fillColor);
        String pct = (int) Math.round(ratio * 100) + "%";
        ctx.drawString(font, pct, right - font.width(pct), y, fillColor, false);
        y += 14;

        if (vault.warning() != null) {
            ctx.drawString(font, "⚠ " + vault.warning(), left, y, NeoGuiTex.RED, false);
            y += 12;
        }

        NeoGuiTex.tileH(ctx, "tex_divider", left, right, y, 48, 6);
        y += 6;
        DailyBucket b = aggregator.today();
        long sale = b.incomeByCategory.getOrDefault(TransactionRecord.CAT_FLEA_SALE, 0L);
        long buy = b.expenseByCategory.getOrDefault(TransactionRecord.CAT_FLEA_ORDER, 0L);
        long dep = b.transferOutByCategory.getOrDefault(TransactionRecord.CAT_FLEA_VAULT, 0L);
        long wd = b.transferInByCategory.getOrDefault(TransactionRecord.CAT_FLEA_VAULT, 0L);

        y = vaultRow(ctx, "오늘 플리 판매", "+" + GoldFormat.format(sale), left, right, y, NeoGuiTex.GREEN);
        y = vaultRow(ctx, "오늘 플리 구매", "-" + GoldFormat.format(buy), left, right, y, NeoGuiTex.RED);
        y = vaultRow(ctx, "금고 입금", "+" + GoldFormat.format(dep), left, right, y, NeoGuiTex.BLUE);
        y = vaultRow(ctx, "금고 출금", "-" + GoldFormat.format(wd), left, right, y, NeoGuiTex.BLUE);
        long change = sale - buy + dep - wd;
        vaultRow(ctx, "오늘 순변화", GoldFormat.signed(change), left, right, y,
                change > 0 ? NeoGuiTex.GREEN : (change < 0 ? NeoGuiTex.RED : NeoGuiTex.TEXT));
    }

    private int vaultRow(GuiGraphics ctx, String label, String value, int left, int right, int y, int color) {
        ctx.drawString(font, label, left, y, NeoGuiTex.LABEL, false);
        ctx.drawString(font, value, right - font.width(value), y, color, false);
        return y + ROW_H;
    }

    // ── 관리 탭 (수동 입력 + 오늘 초기화) ──
    private void renderManage(GuiGraphics ctx, int x, int y) {
        int left = x + PAD;
        ctx.drawString(font, "금액", left, y, NeoGuiTex.LABEL, false);
        ctx.drawString(font, "설명 (선택)", x + PAD + 116, y, NeoGuiTex.LABEL, false);
        NeoGuiTex.sprite(ctx, "tex_input", x + PAD, y + 12, 110, 18);
        NeoGuiTex.sprite(ctx, "tex_input", x + PAD + 116, y + 12, W - PAD * 2 - 116, 18);
        ctx.drawString(font, "'수동' 카테고리로 오늘 집계에 반영됩니다.", left, y + 58, NeoGuiTex.LABEL, false);

        // 날짜 지정 초기화 — 잘못 기록된 특정 날짜만 지울 때
        ctx.drawString(font, "날짜  예) 2026-07-28 · 비우면 오늘", left, y + 90, NeoGuiTex.LABEL, false);
        NeoGuiTex.sprite(ctx, "tex_input", x + PAD, y + 102, 110, 18);
        ctx.drawString(font, "내역 탭에서 항목을 Shift+클릭하면 그 기록만 삭제됩니다.",
                left, y + 124, NeoGuiTex.LABEL, false);
    }

    // ── 설정 탭 (HUD 위치) ──
    private void renderSettings(GuiGraphics ctx, int x, int y) {
        int left = x + PAD;
        ctx.drawString(font, "HUD 위치는 아래 버튼, 상세 설정은", left, y, NeoGuiTex.LABEL, false);
        // sHud 버튼(y+14)은 위젯이 렌더
        String pct = "HUD 투명도  " + Math.round(config.hudOpacity * 100) + "%";
        int tw = font.width(pct);
        ctx.drawString(font, pct, x + (W - tw) / 2, y + 47, NeoGuiTex.TEXT, false);
        // -/+ 버튼(y+42)은 위젯이 렌더
        ctx.drawString(font, "그 외 설정은 config/billding/config.json 파일을 편집하세요.", left, y + 74, NeoGuiTex.LABEL, false);
    }
}
