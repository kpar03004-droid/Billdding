package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 플로리스트 향장품 아이콘 매칭 검증.
 *
 * <p>{@link CustomItemIcon#match} 는 ENTRIES 를 <b>앞에서부터 훑어 첫 매칭</b>을 쓴다.
 * 향장품이 요리 식재료 "오일"보다 뒤에 있으면 "크리스텔라 오일"이 식용유 아이콘으로 빠진다.
 * 이 테스트가 그 순서 의존을 고정한다.
 */
class FloristIconTest {

    private static float cmd(String label) {
        CustomItemIcon.Icon i = CustomItemIcon.match(label);
        assertNotNull(i, label + " 아이콘이 매칭되지 않음");
        return i.cmd();
    }

    @Test
    void 향장품_5종이_각자_아이콘으로_매칭된다() {
        assertEquals(2054005f, cmd("아쿠아네타 앰플"));
        assertEquals(2054033f, cmd("루밀리아 디퓨저"));
        assertEquals(2054038f, cmd("솔라리스티 캔들"));
        assertEquals(2054014f, cmd("벨라로제 퍼퓸"));
        assertEquals(2054023f, cmd("크리스텔라 오일"));
    }

    @Test
    void 크리스텔라_오일이_요리용_식용유로_빠지지_않는다() {
        // 2013005 = 요리 식재료 오일(레몬 올리브 오일). 순서가 뒤집히면 이 값이 나온다.
        assertEquals(2054023f, cmd("크리스텔라 오일"), "향장품이 '오일' 규칙보다 먼저 걸려야 함");
        // 요리용 오일 자체는 그대로 동작해야 한다
        assertEquals(2013005f, cmd("오일"));
    }

    @Test
    void 성급이_붙어도_같은_아이콘으로_걸린다() {
        // 라벨은 "[3성 향장품] 벨라로제 퍼퓸" 처럼 앞뒤에 태그가 붙어 올 수 있다(contains 매칭)
        assertEquals(2054014f, cmd("[3성 향장품] 벨라로제 퍼퓸"));
        assertEquals(2054038f, cmd("솔라리스티 캔들 ★★★"));
    }
}
