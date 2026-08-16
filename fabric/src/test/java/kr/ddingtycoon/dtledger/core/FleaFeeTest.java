package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 내 플리마켓 판매 수수료 5% 반영 검증 (2026-07-27 제보).
 * 수수료는 선차감되어 금고에 순수령액만 들어오므로 INCOME 도 순수령액이어야 하고,
 * 별도 수수료 지출 레코드를 만들면 손익에서 이중 차감되므로 만들지 않는다.
 */
class FleaFeeTest {

    @Test
    void 플리판매는_수수료_차감후_순수령액만_수입() {
        DtConfig cfg = new DtConfig(); // fleaSaleFeePercent = 5.0
        TransferClassifier c = new TransferClassifier(cfg);

        TradeSignal sig = new TradeSignal(TradeSignal.Type.FLEA_SALE,
                1_000_000, 0, 10, "코룸 정동석", "raw");
        List<TransactionRecord> out = c.classify(sig, TransferClassifier.CrossCheck.NONE, 0L);

        assertEquals(1, out.size(), "수수료 레코드를 따로 만들면 이중 차감됨");
        TransactionRecord r = out.get(0);
        assertEquals(TransactionRecord.Kind.INCOME, r.kind);
        assertEquals(950_000, r.amount, "1,000,000 - 5% = 950,000");
        assertEquals(TransactionRecord.CAT_FLEA_SALE, r.category);
        assertTrue(r.note != null && r.note.contains("수수료"), "총액·수수료를 note 에 남겨 추적 가능해야 함");
    }

    @Test
    void 플리마켓_직접판매는_메시지의_순수령액그대로() {
        // 2026-07-27 실측(제보 #4): "플리마켓에 64개를 판매하여 45,410골드(수수료: 2,390골드)를 받았습니다."
        // 45,410 + 2,390 = 47,800 → 수수료 정확히 5%. 메시지가 순수령액을 주므로 재차감 금지.
        CurrencyParser parser = CurrencyParser.createDefault();
        TradeSignal sig = parser.parse("플리마켓에 64개를 판매하여 45,410골드(수수료: 2,390골드)를 받았습니다.");

        assertEquals(TradeSignal.Type.FLEA_DIRECT_SALE, sig.type);
        assertEquals(45_410, sig.amount);
        assertEquals(2_390, sig.fee);
        assertEquals(64, sig.qty);
        assertEquals(45_410, sig.expectedMagnitude(), "수수료는 잔고에서 따로 안 빠짐");

        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new TransferClassifier(cfg)
                .classify(sig, TransferClassifier.CrossCheck.NONE, 0L);

        assertEquals(1, out.size(), "선차감 수수료를 지출로 또 잡으면 이중 차감");
        assertEquals(TransactionRecord.Kind.INCOME, out.get(0).kind);
        assertEquals(45_410, out.get(0).amount);
        assertTrue(out.get(0).note.contains("47,800"), "총액을 note 로 추적 가능해야 함");
    }

    @Test
    void 플리마켓_직접판매_채팅겹침N배() {
        // "[2]" 겹침 표시 → 금액·수량 2배
        TradeSignal sig = CurrencyParser.createDefault()
                .parse("플리마켓에 64개를 판매하여 7,600골드(수수료: 400골드)를 받았습니다. [2]");
        assertEquals(15_200, sig.amount);
        assertEquals(128, sig.qty);
    }

    @Test
    void 유저상점_판매는_품목과_무관하게_유저상점() {
        // 2026-07-28 사용자 투표 B안 — 전문가 산출물이라도 유저상점에 팔면 "유저상점"
        DtConfig cfg = new DtConfig();
        TransferClassifier c = new TransferClassifier(cfg);

        List<TransactionRecord> out = c.classify(
                new TradeSignal(TradeSignal.Type.USERSHOP_SALE, 111_150, 0, 0, "중급 라이프스톤", "raw"),
                TransferClassifier.CrossCheck.NONE, 0L);

        assertEquals(1, out.size());
        assertEquals("유저상점", out.get(0).category, "채광 산출물이어도 판매처 기준");
        assertEquals("중급 라이프스톤", out.get(0).label, "무엇을 팔았는지는 라벨로 남음");

        // NPC·무역 판매는 그대로 활동(전문가)별
        List<TransactionRecord> npc = c.classify(
                new TradeSignal(TradeSignal.Type.SALE, 286_028, 0, 0, "귀중품", "raw"),
                TransferClassifier.CrossCheck.NONE, 0L);
        assertEquals("세공", npc.get(0).category);
    }

    @Test
    void 수수료율_0이면_총액그대로() {
        DtConfig cfg = new DtConfig();
        cfg.fleaSaleFeePercent = 0;
        TransferClassifier c = new TransferClassifier(cfg);

        List<TransactionRecord> out = c.classify(
                new TradeSignal(TradeSignal.Type.FLEA_SALE, 1_000_000, 0, 1, "x", "raw"),
                TransferClassifier.CrossCheck.NONE, 0L);

        assertEquals(1_000_000, out.get(0).amount);
    }

    @Test
    void 잠수중_놓친_판매는_금고_재동기화_차액으로_보정() {
        // 2026-07-28 확인: 잠수 중엔 다른 서버로 옮겨져 플리 판매 채팅이 아예 안 옴 →
        // "/플리마켓 금고" 실측 잔액과 추적값의 차액을 놓친 거래로 보정한다.
        DtConfig cfg = new DtConfig();
        VaultTracker vault = new VaultTracker(1_000_000, cfg.vaultLimit, v -> {});

        long missed = vault.syncFromServer(1_500_000);
        assertEquals(500_000, missed);
        assertEquals(1_500_000, vault.balance());

        TransactionRecord rec = VaultTracker.missedRecord(missed, 0L);
        assertEquals(TransactionRecord.Kind.INCOME, rec.kind);
        assertEquals(500_000, rec.amount);
        assertTrue(rec.label.contains("잠수"));
        // 이 레코드를 금고에 다시 반영하면 이중계상 — 카테고리가 금고 추적 대상이 아니어야 함
        vault.onRecord(rec);
        assertEquals(1_500_000, vault.balance(), "보정 레코드가 금고에 또 더해지면 안 됨");
    }

    @Test
    void 금고_최초설정은_보정하지_않음() {
        // 미설정(-1) 상태에서의 첫 동기화는 기준선일 뿐 — 전액을 수입으로 잡으면 안 됨
        VaultTracker vault = new VaultTracker(-1, new DtConfig().vaultLimit, v -> {});
        assertEquals(0, vault.syncFromServer(12_635_710L));
        assertEquals(12_635_710L, vault.balance());
    }

    @Test
    void 금고잔액도_순수령액으로_증가() {
        // VaultTracker 는 CAT_FLEA_SALE 레코드 금액을 그대로 더하므로,
        // 순수령액으로 기록돼야 금고 잔액이 실제와 어긋나지 않음.
        DtConfig cfg = new DtConfig();
        TransferClassifier c = new TransferClassifier(cfg);
        VaultTracker vault = new VaultTracker(0, cfg.vaultLimit, v -> {});

        List<TransactionRecord> out = c.classify(
                new TradeSignal(TradeSignal.Type.FLEA_SALE, 1_000_000, 0, 1, "x", "raw"),
                TransferClassifier.CrossCheck.NONE, 0L);
        out.forEach(vault::onRecord);

        assertEquals(950_000, vault.balance());
    }
}
