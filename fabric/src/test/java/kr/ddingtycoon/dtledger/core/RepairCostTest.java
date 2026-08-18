package kr.ddingtycoon.dtledger.core;

import kr.ddingtycoon.dtledger.util.BalanceExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2026-08-08 제보 회귀 방지.
 *
 * <p>제보 1: "수리 소모 골드 : 0골드"(무료 수리)인데 지출 3,638원이 찍힘 — 직전 수리 비용 재사용.
 * <p>제보 2: 0원 수리에 보유 골드 전액(1.7억)이 지출로 찍힘 — 잔고를 엉뚱한 숫자로 읽어 생긴 가짜 ΔG.
 */
class RepairCostTest {

    // ── 무료 수리와 "못 읽음"의 구분 ──

    @Test
    void 무료_수리는_0으로_읽히고_미검출은_null() {
        // 영상 프레임에서 실제로 확인된 문구
        assertEquals(0L, RepairCostLore.parseRepairCost("  - 수리 소모 골드 : 0골드"),
                "0골드는 '무료'라는 정보 — null 이 아니다");
        assertEquals(5286L, RepairCostLore.parseRepairCost("  - 수리 소모 골드 : 5,286골드"));
        assertNull(RepairCostLore.parseRepairCost("  - 수리 소모 레벨 : 0 (0경험치)"),
                "비용 줄이 아니면 null");
        assertNull(RepairCostLore.parseRepairCost("아무 말"));
        assertNull(RepairCostLore.parseRepairCost(null));
    }

    @Test
    void 품질회복도_0과_미검출을_구분한다() {
        assertEquals(0L, RepairCostLore.parseRestoreCost("- 회복 비용 : 0골드"));
        assertEquals(10000L, RepairCostLore.parseRestoreCost("- 회복 비용 : 10000골드"));
        assertNull(RepairCostLore.parseRestoreCost("- 수리 소모 골드 : 5,286골드"),
                "수리 비용을 회복 비용으로 읽으면 안 됨");
    }

    @Test
    void 강화_비용은_같은_줄의_루비를_빼고_골드만_읽는다() {
        // 2026-08-14 실측: "- 강화 비용 : 700,000골드, 10루비"
        assertEquals(700_000L, RepairCostLore.parseEnhanceCost("  - 강화 비용 : 700,000골드, 10루비"),
                "루비를 금액에 섞으면 안 됨");
        assertEquals(500_000L, RepairCostLore.parseEnhanceCost("- 강화 비용: 500,000 골드"));
        // 재료가 부족하면 비용 줄 자체가 안 뜬다 — 그때는 null
        assertNull(RepairCostLore.parseEnhanceCost("  - 하급 라이프스톤 (8개)"));
        assertNull(RepairCostLore.parseEnhanceCost("  ! 장비 강화 재료가 부족합니다."));
        assertNull(RepairCostLore.parseEnhanceCost("  - 성공 확률 : 5%"));
        // 다른 창의 비용 줄과 섞이지 않는다
        assertNull(RepairCostLore.parseEnhanceCost("  - 수리 소모 골드 : 5,286골드"));
        assertNull(RepairCostLore.parseRepairCost("  - 강화 비용 : 700,000골드, 10루비"));
    }

    @Test
    void 각인_비용은_강화와_같은_형식이고_골드만_읽는다() {
        // 2026-08-18 실측: "- 각인 비용 : 500,000골드, 3루비"
        assertEquals(500_000L, RepairCostLore.parseEngraveCost("  - 각인 비용 : 500,000골드, 3루비"),
                "루비를 금액에 섞으면 안 됨");
        assertEquals(1_200_000L, RepairCostLore.parseEngraveCost("- 각인 비용: 1,200,000 골드"));
        // 강화 비용과 서로 섞이지 않는다
        assertNull(RepairCostLore.parseEngraveCost("  - 강화 비용 : 700,000골드, 10루비"));
        assertNull(RepairCostLore.parseEnhanceCost("  - 각인 비용 : 500,000골드, 3루비"));
        // 재료 부족 등으로 비용 줄이 없으면 null
        assertNull(RepairCostLore.parseEngraveCost("  ❗ 각인 재료를 넣어주세요."));
    }

    // ── 잔고 오독 방어 ──

    /**
     * 2026-08-10 실패 경험 회귀 방지 — 마커('골드') 줄만 신뢰하도록 좁혔다가
     * <b>잔고를 영영 못 읽어</b> 전문가 스킬 업그레이드·강화·각인이 전부 누락됐다.
     * 띵타이쿤의 골드 표시는 아이콘 + 숫자라 '골드'라는 글자가 없다.
     */
    @Test
    void 골드_글자가_없어도_잔고를_읽어야_한다() {
        // 띵타이쿤 상단 바는 아이콘 + 숫자뿐 — '골드' 글자가 없다.
        // 마커 줄만 신뢰하도록 좁혔다가 잔고를 영영 못 읽어 되돌린 적 있다(2026-08-10).
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(3_315_718L,
                ex.extract(List.of(" 3,315,718", " 11,922", " 2,000"),
                        "골드", "([0-9][0-9,]{2,})"));
    }

    /**
     * 2026-08-14 제보의 핵심 — 골드 줄이 잠깐 사라졌을 때 다른 숫자로 갈아타면 안 된다.
     * 실측: 실제 잔고 3,315,718 인데 100,000 으로 읽어 ΔG −3,220,718 이 만들어졌고,
     * 5,000골드 강화가 322만짜리로 뒤바뀔 뻔했다.
     */
    /**
     * 2026-08-18 실측 — 의뢰 트래커의 "상점에서 골드 소모하기 (0/100,000)" 를 잔고로 읽어
     * 목표 금액 100,000 을 잔고로 오인했다(실제 잔고는 13,869,528). 그 퀘스트 줄에 "골드"
     * 글자가 있어 마커와 충돌한 게 함정. 진행도 줄은 무조건 배제해야 한다.
     */
    @Test
    void 의뢰_진행도_줄을_잔고로_읽지_않는다() {
        BalanceExtractor ex = new BalanceExtractor();
        String marker = "골드", regex = "([0-9][0-9,]{2,})";

        // 퀘스트 줄이 "골드"를 품고 있어도 잔고로 뽑히면 안 된다
        assertNull(ex.extract(
                List.of("상점에서 골드 소모하기 (0/100,000)", "세렌트 채굴하기 (0/30)"),
                marker, regex),
                "진행도 줄만 있으면 잔고 없음(null)");

        // 진짜 잔고(아이콘+숫자, '골드' 글자 없음)가 섞여 있으면 그걸 잡아야 한다
        assertEquals(13_869_528L, ex.extract(
                List.of("상점에서 골드 소모하기 (0/100,000)", " 13,869,528", "감자 심기 (0/96)"),
                marker, regex),
                "퀘스트 줄을 걸러내고 진짜 잔고를 잡는다");
    }

    @Test
    void 골드_줄이_사라지면_다른_숫자로_갈아타지_않는다() {
        BalanceExtractor ex = new BalanceExtractor();
        String marker = "골드", regex = "([0-9][0-9,]{2,})";
        List<String> gold = List.of(" 3,320,718", " 11,922", " 2,000");

        assertEquals(3_320_718L, ex.extract(gold, marker, regex), "먼저 골드 줄을 잡는다");

        // 골드 줄만 빠지고 의뢰 진행도 같은 게 남은 틱
        List<String> without = List.of(" 11,922", " 2,000", " 100,000");
        assertNull(ex.extract(without, marker, regex), "판단 보류 — 100,000 을 잔고로 쓰면 안 된다");

        // 돌아오면 정상적으로 이어서 읽는다(강화 비용 5,000 차감분)
        assertEquals(3_315_718L,
                ex.extract(List.of(" 3,315,718", " 11,922", " 100,000"), marker, regex));
    }

    @Test
    void 같은_줄이_오래_안_보이면_잠금을_풀고_다시_찾는다() {
        BalanceExtractor ex = new BalanceExtractor();
        String marker = "골드", regex = "([0-9][0-9,]{2,})";
        ex.extract(List.of(" 3,320,718"), marker, regex);

        List<String> other = List.of(" 250,000");
        for (int i = 0; i < 19; i++) {
            assertNull(ex.extract(other, marker, regex), i + "번째 틱은 아직 보류");
        }
        // 20틱(약 1초) 넘게 안 보이면 UI 가 바뀐 것으로 보고 재탐색 — 영영 못 읽는 상태를 피한다
        assertEquals(250_000L, ex.extract(other, marker, regex));
    }

    @Test
    void 월드_전환하면_잠금이_풀린다() {
        BalanceExtractor ex = new BalanceExtractor();
        String marker = "골드", regex = "([0-9][0-9,]{2,})";
        ex.extract(List.of(" 3,320,718"), marker, regex);
        ex.unlock();
        assertEquals(500_000L, ex.extract(List.of(" 500,000"), marker, regex),
                "다른 서버는 표시 모양이 다를 수 있다");
    }

    @Test
    void 마커가_있으면_그_줄을_우선한다() {
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(1_234_567L,
                ex.extract(List.of("은행 45,000,000", "소지금: 1,234,567 골드", "한도 99,999,999"),
                        "골드", "([0-9][0-9,]{2,})"));
    }

    @Test
    void 읽을_숫자가_전혀_없으면_null() {
        BalanceExtractor ex = new BalanceExtractor();
        assertNull(ex.extract(List.of(), "골드", "([0-9][0-9,]{2,})"));
        assertNull(ex.extract(List.of("골드", "레벨 7"), "골드", "([0-9][0-9,]{2,})"),
                "3자리 미만은 금액 토큰이 아님");
    }
}
