package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 합쳐진 ΔG 중복(이중계상) 억제 검증 — 최근 버그(판매 2배, 금고 입금 지출) 회귀 방지. */
class ResolverDuplicateTest {

    private static long sumOf(List<TransactionRecord> out, String category) {
        long s = 0;
        for (TransactionRecord r : out) if (category.equals(r.category)) s += r.amount;
        return s;
    }

    @Test
    void 두_판매가_하나의_ΔG로_합쳐져도_기타_중복_없음() {
        DtConfig cfg = new DtConfig(); // matchWindowMs=1500
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.SALE, 579810, 0, 5, "나우틸러스", "raw"));
        r.onSignal(new TradeSignal(TradeSignal.Type.SALE, 229014, 0, 3, "배 낚시 물고기", "raw"));
        r.onDelta(808824); // 합쳐서 감지된 ΔG

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600); // 신호 만료 → 수입 2건 + 기대치 등록
        r.tick(t0 + 3300); // 델타 만료 → 부분집합 합 일치 → 억제

        assertEquals(0, sumOf(out, "기타"), "합쳐진 ΔG 가 기타로 중복 계상되면 안 됨");
        assertEquals(2, out.size());
    }

    @Test
    void 금고_입금_두건이_합쳐져도_지출_중복_없음() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.FLEA_VAULT_DEPOSIT, 3_000_000, 0, 0, "금고 입금", "raw"));
        r.onSignal(new TradeSignal(TradeSignal.Type.FLEA_VAULT_DEPOSIT, 1_200_000, 0, 0, "금고 입금", "raw"));
        r.onDelta(-4_200_000); // 합쳐진 출금 ΔG

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 3300);

        // 금고 입금은 이체(손익 제외)로만 잡히고, 기타 지출로 중복되면 안 됨
        assertEquals(0, sumOf(out, "기타"));
        long transfer = sumOf(out, TransactionRecord.CAT_FLEA_VAULT);
        assertEquals(4_200_000, transfer);
    }

    @Test
    void 한_판매의_ΔG가_둘로_쪼개져도_기타_중복_없음() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.SALE, 1_270_060, 0, 5, "나우틸러스의 손", "raw"));
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600); // 판매 확정 + 기대 예산 1,270,060 등록
        // ΔG 가 잔고 애니메이션으로 둘로 쪼개져 들어옴
        r.onDelta(885_985);
        r.onDelta(384_075);
        r.tick(t0 + 3300); // 두 델타 만료 → 예산에서 부분 소모 → 둘 다 억제

        assertEquals(0, sumOf(out, "기타"), "쪼개진 ΔG 가 기타로 중복 계상되면 안 됨");
        assertEquals(1, out.size());
    }

    @Test
    void 무기강화는_ΔG로_강화지출_한건() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 금액은 메시지에 없음(amountFromDelta=true), 잔고 -350,000 로 강화 비용 산정
        r.onSignal(new TradeSignal(TradeSignal.Type.WEAPON_ENHANCE, 0, 0, 0,
                "세이지 곡괭이 +7강 강화", "raw", true));
        r.onDelta(-350_000);

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000); // ΔG금액형은 연장 대기창(15s) 이후 확정

        assertEquals(1, out.size());
        TransactionRecord rec = out.get(0);
        assertEquals(TransactionRecord.Kind.EXPENSE, rec.kind);
        assertEquals("강화", rec.category);
        assertEquals(350_000, rec.amount);
        assertEquals(0, sumOf(out, "전문가"), "무기 강화가 전문가 카테고리로 새면 안 됨");
    }

    @Test
    void 각인은_ΔG로_각인지출() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onDelta(-80_000);
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000); // ΔG금액형 연장 대기창(15s)

        assertEquals(1, out.size());
        TransactionRecord rec = out.get(0);
        assertEquals(TransactionRecord.Kind.EXPENSE, rec.kind);
        assertEquals("각인", rec.category);
        assertEquals(80_000, rec.amount);
    }

    @Test
    void 연속_동일금액_각인실패는_dedup_예외로_전부집계() {
        // 각인 실패를 같은 비용으로 연달아 시도 → 동일 ΔG 라도 대기 신호가 있으면 억제 안 함
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onDelta(-50_000); // 동일 금액 3연속(수 ms 간격) — dedup 창 안이지만 예외 적용
        r.onDelta(-50_000);
        r.onDelta(-50_000);

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000); // ΔG금액형 연장 대기창(15s)

        assertEquals(3, out.size(), "동일금액 연속 각인 실패가 dedup 으로 과소집계되면 안 됨");
        assertEquals(150_000, sumOf(out, "각인"));
    }

    @Test
    void 강화_4회가_변동3번으로_들어와도_4건_700000씩() {
        // 2026-07-29 실측: 강화 4회(각 700,000)인데 잔고는 -700,000 / -1,400,000 / -700,000
        // 세 번으로 들어와 3건(700,000·1,400,000·700,000)으로 기록됨.
        // 개별 변동을 인원수로 나누면 700,000÷4=175,000 처럼 엉뚱하게 쪼개지므로,
        // 변동을 전부 걷어 총액(2,800,000)을 시도 횟수(4)로 나눠야 정확하다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        for (int i = 0; i < 4; i++) {
            r.onSignal(new TradeSignal(TradeSignal.Type.WEAPON_ENHANCE, 0, 0, 0, "장비 강화 실패", "raw", true));
        }
        r.onDelta(-700_000);
        r.onDelta(-1_400_000);
        r.onDelta(-700_000);

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(4, out.size(), "4회 시도면 4건");
        assertEquals(2_800_000, sumOf(out, "강화"));
        for (TransactionRecord rec : out) assertEquals(700_000, rec.amount);
    }

    @Test
    void 수리창에서_읽은_비용으로_합산금액을_정확히_분해() {
        // 도구마다 비용이 다르다(품질회복: 10번 한 도구 110,000 / 새 도구 10,000).
        // 규칙으로 추정할 수 없으므로 창에 실제로 떠 있던 금액을 조합해 복원한다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 110_000); // 창에서 본 값들
        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 10_000);

        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onDelta(-120_000); // 합쳐 들어옴

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(2, out.size(), "창에서 본 금액으로 2건 복원");
        assertEquals(120_000, sumOf(out, "수리"));
        List<Long> amounts = out.stream().map(rec -> rec.amount).sorted().toList();
        assertEquals(List.of(10_000L, 110_000L), amounts, "실제 있었던 금액 그대로");
    }

    @Test
    void 잔고변동을_못봐도_창에_적힌_비용으로_기록() {
        // 2026-07-30 실측: 채팅·비용 읽기는 되는데 기록이 안 됨 = 잔고 변동을 못 잡은 것.
        // 창에 가격이 떠 있었으므로 통째로 누락시키지 말고 그 금액으로 기록한다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 5_286);
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        // ΔG 없음

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(1, out.size(), "잔고 변동을 못 봐도 기록돼야 함");
        assertEquals(5_286, out.get(0).amount);
        assertEquals("수리", out.get(0).category);
        assertEquals(TransactionRecord.Confidence.MEDIUM, out.get(0).confidence,
                "교차검증 안 된 값이므로 확신도는 낮게");
    }

    @Test
    void 창금액도_ΔG도_없으면_기록하지_않음() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        // 2026-08-13 변경: 조용히 버리면 유저가 빠진 걸 모른다 → 금액 0 "미확인"으로 흔적을 남긴다.
        // 없는 금액을 지어내지 않는다는 원칙은 그대로다.
        assertEquals(1, out.size(), "흔적은 남긴다");
        assertEquals(0, out.get(0).amount, "근거가 없으면 금액을 지어내지 않음");
        assertEquals(0, sumOf(out, "수리"), "합계에는 영향 없음");
    }

    @Test
    void 수리도_창에서_읽은_비용으로_분해() {
        // 수리 비용은 인챈트에 따라 도구마다 다름(같은 도구는 일정)
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.TOOL_REPAIR, 5_286);

        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.TOOL_REPAIR, 0, 0, 0, "도구 수리", "raw", true));
        r.onDelta(-10_572); // 5,286 × 2 합산

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(2, out.size());
        for (TransactionRecord rec : out) assertEquals(5_286, rec.amount);
    }

    @Test
    void 품질회복은_비용이_제각각이라_금액을_추정하지_않는다() {
        // 품질 회복 비용은 도구별 회복 횟수에 따라 다르다(10번 한 도구는 110,000, 새 도구는 10,000).
        // 합산 금액을 나눠 추정하면 실제로 없던 금액이 만들어지므로, 관측된 변동만 그대로 기록한다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        // 2026-08-08 변경: ΔG 를 그대로 적던 예전 동작은 잔고를 한 틱 잘못 읽으면 그 오차가
        // 통째로 수리비가 됐다(실측: 0원 수리에 −170,901,145). 이제 창 금액만 근거로 쓴다.
        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 110_000);
        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 10_000);
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onDelta(-120_000); // 110,000 + 10,000 이 합쳐 들어옴(도구가 서로 다름)

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(2, out.size(), "창에서 본 금액 조합으로 정확히 복원 — 평균으로 추정하지 않음");
        assertEquals(120_000, sumOf(out, "수리"));
    }

    @Test
    void 품질회복_변동이_따로_오면_각각_그대로() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 110_000);
        r.noteGuiCost(TradeSignal.Type.QUALITY_RESTORE, 10_000);
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.QUALITY_RESTORE, 0, 0, 0, "품질 회복", "raw", true));
        r.onDelta(-110_000);
        r.onDelta(-10_000);

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(2, out.size());
        assertEquals(120_000, sumOf(out, "수리"));
    }

    @Test
    void 각인_연속시도의_합산ΔG는_건수만큼_분할() {
        // 2026-07-29 실측: 각인 3회(각 230,000)인데 잔고가 230,000 + 460,000 두 번으로 들어와
        // 2건(230,000 / 460,000)으로 기록됨 — 총액은 맞지만 건수가 안 맞음
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onSignal(new TradeSignal(TradeSignal.Type.ENGRAVE, 0, 0, 0, "장비 각인 실패", "raw", true));
        r.onDelta(-460_000); // 2회분이 합쳐 들어옴

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(2, out.size(), "2회 시도면 2건으로 쪼개져야 함");
        assertEquals(460_000, sumOf(out, "각인"));
        for (TransactionRecord rec : out) assertEquals(230_000, rec.amount);
    }

    @Test
    void 남의_은행거래는_내_잔고가_안움직여서_스킵() {
        // 마을 은행은 공용이라 남의 입출금도 내 채팅에 뜬다(2026-07-27 제보).
        // 닉네임 유무는 서버 구현에 달렸지만 "내 잔고 변동"은 확실한 근거다.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.BANK_DEPOSIT, 1_000_000, 0, 0, "은행 입금", "raw"));
        // ΔG 없음 = 내 잔고 그대로 = 남의 거래
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(0, out.size(), "남의 은행 거래가 내 장부에 들어오면 안 됨");
    }

    @Test
    void 내_은행거래는_잔고변동과_함께_기록() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.BANK_WITHDRAW, 500_000, 0, 0, "은행 출금", "raw"));
        r.onDelta(500_000); // 내 잔고 증가 = 내가 출금

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000);

        assertEquals(1, out.size());
        assertEquals(500_000, sumOf(out, "은행"));
    }

    @Test
    void 은행입금_채팅2줄이면_한건만_계상() {
        // 2026-07-27 제보: 입금 시 채팅이 2번 떠서 지출이 2번 잡힘
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.BANK_DEPOSIT, 1_000_000, 0, 0, "은행 입금", "raw"));
        r.onSignal(new TradeSignal(TradeSignal.Type.BANK_DEPOSIT, 1_000_000, 0, 0, "은행 입금", "raw"));
        r.onDelta(-1_000_000);

        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 16_000); // 은행은 잔고 확인을 위해 더 기다림

        assertEquals(1, out.size(), "같은 입금이 2줄로 방송돼도 1건만 기록돼야 함");
        assertEquals(1_000_000, sumOf(out, "은행"));
    }

    @Test
    void 강화_ΔG가_늦게_와도_지출_기록() {
        // 2026-07-27 제보: 낚싯대 강화·각인 지출이 안 잡힘 — GUI 거래는 잔고 갱신이 늦어
        // 기본 시간창(1.5s) 안에 ΔG 가 안 들어오면 통째로 스킵되던 문제.
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);

        r.onSignal(new TradeSignal(TradeSignal.Type.WEAPON_ENHANCE, 0, 0, 0, "낚싯대 강화 성공", "raw", true));
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);          // 기본 시간창 경과 — 아직 버리면 안 됨
        assertEquals(0, out.size(), "ΔG 대기 중에는 아직 확정 전");

        r.onDelta(-350_000);        // 잔고 표시가 뒤늦게 갱신
        r.tick(t0 + 16_000);        // 연장 대기창(15s) 경과 → 확정

        assertEquals(1, out.size(), "늦게 온 ΔG 로도 강화 지출이 기록돼야 함");
        assertEquals(350_000, sumOf(out, "강화"));
    }

    @Test
    void 단일_거래는_정상_한건() {
        DtConfig cfg = new DtConfig();
        List<TransactionRecord> out = new ArrayList<>();
        TransactionResolver r = new TransactionResolver(cfg, new TransferClassifier(cfg), out::add);
        r.onSignal(new TradeSignal(TradeSignal.Type.SALE, 100000, 0, 1, "코룸 정동석", "raw"));
        r.onDelta(100000);
        long t0 = System.currentTimeMillis();
        r.tick(t0 + 1600);
        r.tick(t0 + 3300);
        assertEquals(0, sumOf(out, "기타"));
        assertEquals(1, out.size());
    }
}
