package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 형광 제련품(2026-09-09 패치) — 분류와 아이콘 검증.
 *
 * <p>제련품은 세레니티 NPC 로니에게 판매한다. 로니는 귀중품(세공)도 사는 상인이라
 * 채널만으로는 구분이 안 되고, 품목 이름으로 갈라야 한다.
 *
 * <p>원광·광물 큐브·광물 캔디는 귀속이라 거래가 안 돼 집계에 뜨지 않는다 — 제련품만 다룬다.
 */
class FluorescentMineTest {

    private static String cat(String label) {
        return SaleCategory.of(label, label);
    }

    private static float cmd(String label) {
        CustomItemIcon.Icon i = CustomItemIcon.match(label);
        assertNotNull(i, label + " 아이콘이 매칭되지 않음");
        return i.cmd();
    }

    @Test
    void 제련품_5종이_형광_제련품으로_잡힌다() {
        assertEquals("형광 제련품", cat("오르딘 미니 망치"));
        assertEquals("형광 제련품", cat("루미트 강철 방패"));
        assertEquals("형광 제련품", cat("크레온 장인 석궁"));
        assertEquals("형광 제련품", cat("벨릭 사냥 스피어"));
        assertEquals("형광 제련품", cat("세르칸 초승달 단검"));
    }

    @Test
    void 등급_태그가_붙어도_걸린다() {
        // 판매 채팅 라벨에 등급 태그가 남는다: "[NORMAL] 오르딘 미니 망치"(2026-09-09 실측)
        assertEquals("형광 제련품", SaleCategory.of("[NORMAL] 오르딘 미니 망치", "[NORMAL] 오르딘 미니 망치"));
        assertEquals("형광 제련품", SaleCategory.of("[MYTHIC] 세르칸 초승달 단검", "[MYTHIC] 세르칸 초승달 단검"));
    }

    @Test
    void 기존_카테고리를_침범하지_않는다() {
        // '벨릭 사냥 스피어'의 '사냥', '크레온 장인 석궁'의 '석' 등이 다른 규칙을 건드리면 안 된다.
        assertEquals("사냥전문가", cat("건강한 표범의 가죽"));
        assertEquals("채광전문가", cat("세렌트 주괴"));
        assertEquals("세공", cat("키론 오르골"));          // '오르딘'과 앞 두 글자가 같다
        assertEquals("노크틸라", cat("루미디아 조각"));     // '루미트'와 앞 두 글자가 같다
        assertEquals("판매", cat("스킬 펄스"));            // 제련 재료지만 형광 산출물은 아니다
    }

    @Test
    void 제련품_5종이_각자_아이콘으로_매칭된다() {
        assertEquals(2058101f, cmd("오르딘 미니 망치"));
        assertEquals(2058105f, cmd("루미트 강철 방패"));
        assertEquals(2058102f, cmd("크레온 장인 석궁"));
        assertEquals(2058103f, cmd("벨릭 사냥 스피어"));
        assertEquals(2058104f, cmd("세르칸 초승달 단검"));
    }

    @Test
    void 등급_태그가_붙은_라벨도_아이콘이_걸린다() {
        assertEquals(2058101f, cmd("[NORMAL] 오르딘 미니 망치"));
        assertEquals(2058104f, cmd("[MYTHIC] 세르칸 초승달 단검"));
    }
}
