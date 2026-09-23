package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 영혼 명품(2026-09-23 가죽 공예 Beta) — 분류와 아이콘 검증.
 *
 * <p>명품 이름에 "가죽"이 들어가서, 판정 순서가 뒤집히면 사냥전문가(전리품)로 빠진다.
 * 그 순서와 재료 가죽과의 구분을 여기서 고정한다.
 */
class SoulGoodsTest {

    private static String cat(String label) {
        return SaleCategory.of(label, label);
    }

    private static float cmd(String label) {
        CustomItemIcon.Icon i = CustomItemIcon.match(label);
        assertNotNull(i, label + " 아이콘이 매칭되지 않음");
        return i.cmd();
    }

    @Test
    void 명품_5종이_영혼_명품으로_잡힌다() {
        assertEquals("영혼 명품", cat("고독한 가죽 키링"));
        assertEquals("영혼 명품", cat("신비한 가죽 팔찌"));
        assertEquals("영혼 명품", cat("활기찬 가죽 장갑"));
        assertEquals("영혼 명품", cat("특이한 가죽 파우치"));
        assertEquals("영혼 명품", cat("화끈한 가죽 핸드백"));
    }

    @Test
    void 등급_태그가_붙은_실제_판매_라벨() {
        // 2026-09-24 실측: "[LEGENDARY] 특이한 가죽 파우치 아이템 1개를 150,000골드에 판매하셨습니다."
        assertEquals("영혼 명품", cat("[LEGENDARY] 특이한 가죽 파우치"));
        assertEquals(2059106f, cmd("[LEGENDARY] 특이한 가죽 파우치"));
        assertEquals("영혼 명품", cat("[NORMAL] 고독한 가죽 키링"));
        assertEquals("영혼 명품", cat("[MYTHIC] 화끈한 가죽 핸드백"));
        assertEquals(2059104f, cmd("[MYTHIC] 화끈한 가죽 핸드백"));
    }

    @Test
    void 재료_가죽과_기존_전리품은_사냥전문가_그대로() {
        assertEquals("사냥전문가", cat("고독한 영혼 가죽"));   // 명품 재료 — "고독한 가죽" 을 포함하지 않는다
        assertEquals("사냥전문가", cat("혼이 깃든 가죽"));
        assertEquals("사냥전문가", cat("기린의 가죽"));
        assertEquals("사냥전문가", cat("건강한 표범의 가죽"));
    }

    @Test
    void 명품_아이콘_5종() {
        assertEquals(2059105f, cmd("고독한 가죽 키링"));
        assertEquals(2059102f, cmd("신비한 가죽 팔찌"));
        assertEquals(2059103f, cmd("활기찬 가죽 장갑"));
        assertEquals(2059106f, cmd("특이한 가죽 파우치"));
        assertEquals(2059104f, cmd("화끈한 가죽 핸드백"));
    }
}
