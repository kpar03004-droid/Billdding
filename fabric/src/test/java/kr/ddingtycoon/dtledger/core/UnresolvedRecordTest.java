package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 금액을 못 알아낸 거래를 <b>조용히 버리지 않는다</b>는 규칙 검증.
 *
 * <p>2026-08-13 제보: 전문가 스킬 업그레이드 지출이 통째로 빠졌는데, 유저는 총액을 직접
 * 대조하기 전까지 몰랐다. 흔적을 남겨야 "왜 없죠?"가 아니라 "미확인으로 떴어요"가 된다.
 */
class UnresolvedRecordTest {

    private static void settle(TransactionResolver r) {
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 16_000);
        r.tick(t0 + 32_000);
    }

    @Test
    void 금액을_못_알아내면_미확인으로_남긴다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);
        r.setNotifier(notices::add);

        // 창 금액도 없고 ΔG 도 없다 — 예전엔 로그 한 줄 남기고 사라졌다
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        settle(r);

        assertEquals(1, out.size(), "흔적이 남아야 한다");
        TransactionRecord rec = out.get(0);
        assertEquals(0, rec.amount, "금액 0 — 합계를 오염시키지 않는다");
        assertEquals("전문가", rec.category);
        assertEquals("재배학개론 스킬", rec.label);
        assertFalse(rec.countedInPnl, "손익에 포함되면 안 됨");
        assertEquals(TransactionRecord.Confidence.LOW, rec.confidence, "미분류 목록에 뜨도록 LOW");
        assertTrue(rec.note.contains("금액 미확인"));

        assertEquals(1, notices.size(), "채팅으로도 알려야 한다");
        assertTrue(notices.get(0).contains("재배학개론 스킬"));
        assertTrue(notices.get(0).contains("/빌띵 추가 지출"), "바로 입력할 명령을 알려준다");
    }

    @Test
    void 수리도_창_금액을_못_읽으면_미확인() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onDelta(-50_000_000L);   // 엉뚱한 ΔG 는 여전히 신뢰하지 않는다
        settle(r);

        assertEquals(1, out.size());
        assertEquals(0, out.get(0).amount, "믿을 수 없는 ΔG 를 금액으로 쓰지 않는다");
        assertEquals("수리", out.get(0).category);
    }

    @Test
    void 무료_수리는_미확인이_아니라_그냥_기록_안_함() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);
        r.setNotifier(notices::add);

        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 0);   // 창이 0골드라고 말함
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        settle(r);

        assertTrue(out.isEmpty(), "무료는 누락이 아니다 — 알림도 기록도 없어야 함");
        assertTrue(notices.isEmpty());
    }

    @Test
    void 정상_기록된_건은_미확인이_안_생긴다() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);
        r.setNotifier(notices::add);

        r.noteSkillCosts(Map.of("재배학개론", 100_000L));
        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        settle(r);

        assertEquals(1, out.size());
        assertEquals(100_000, out.get(0).amount);
        assertTrue(notices.isEmpty(), "잘 잡혔으면 알림으로 귀찮게 하지 않는다");
    }

    @Test
    void 설정으로_끄면_예전처럼_조용히_버린다() {
        DtConfig cfg = new DtConfig();
        cfg.recordUnresolved = false;
        List<TransactionRecord> out = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);
        r.setNotifier(notices::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.SKILL_UPGRADE, 0, 0, 0, "재배학개론 스킬", "raw", true));
        settle(r);

        assertTrue(out.isEmpty());
        assertTrue(notices.isEmpty());
    }
}
