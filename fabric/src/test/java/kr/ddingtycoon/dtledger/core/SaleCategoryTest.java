package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 사용자 확정 분류(2026-07-21, 띵타이쿤_아이템_정리.md) 검증.
 * 특히 키워드 충돌(심해·코어·아쿠아·펄스·해구·화석) 회귀 방지.
 */
class SaleCategoryTest {

    private static String cat(String label) {
        return SaleCategory.of(label, label);
    }

    @Test
    void 연금_태그와_고유명사() {
        assertEquals("연금", SaleCategory.of("영생의 아쿠티스", "[1성 연금품] 영생의 아쿠티스 ★"));
        assertEquals("연금", cat("수호의 엘릭서"));
        assertEquals("연금", cat("불멸 재생의 영약"));
        assertEquals("연금", cat("파동 에센스"));
        assertEquals("연금", cat("추출된 희석액"));
        assertEquals("연금", cat("침묵의 심해 비약"));
        assertEquals("연금", cat("나우틸러스의 손"));
        assertEquals("연금", cat("리바이던의 깃털")); // 사냥 '깃털'보다 먼저
        assertEquals("연금", cat("아쿠아 펄스 파편"));
        assertEquals("연금", cat("해구의 파동 코어")); // 2026-08-02 제보: '판매'로 새던 건
    }

    @Test
    void 연금_키워드_충돌_회피() {
        // 과거 '심해/코어/아쿠아/펄스/해구' 낱말 키워드가 일으킨 연금 오분류들
        assertEquals("배 낚시", cat("심해 해역 물고기"));
        assertEquals("배 낚시", cat("해저 열수구 코어"));   // 심해 자원 → 배 낚시(#3)
        assertEquals("배 낚시", cat("해구의 화석 연료"));   // 공룡 '화석'과도 충돌했음
        // "파동 코어"(연금)를 넣어도 위 둘이 연금으로 넘어가면 안 된다 — 복합어라 안전
        assertEquals("판매", cat("아쿠아 코인"));
        assertEquals("판매", cat("스킬 펄스"));
    }

    @Test
    void 플로리스트_꽃과_향장품을_한_카테고리로() {
        // 꽃 5종(카이 매입 50,000~500,000G)
        for (String flower : new String[]{"아쿠아네타", "루밀리아", "솔라리스티", "벨라로제", "크리스텔라"}) {
            assertEquals("플로리스트", cat(flower), flower);
            assertEquals("플로리스트", cat(flower + " ★★★"), flower + " 등급");
        }
        // 가공품 5종 — 이름이 "꽃 + 제품" 꼴이라 꽃 이름만으로 걸린다
        assertEquals("플로리스트", cat("아쿠아네타 앰플"));
        assertEquals("플로리스트", cat("루밀리아 디퓨저"));
        assertEquals("플로리스트", cat("솔라리스티 캔들"));
        assertEquals("플로리스트", cat("벨라로제 퍼퓸"));
        assertEquals("플로리스트", cat("크리스텔라 오일"));
        // 실제 채팅 표기(★ 등급 + EPIC 태그, 게임 내 '퍼품' 오탈자)
        assertEquals("플로리스트",
                SaleCategory.of("벨라로제 퍼품", "[EPIC] 벨라로제 퍼품 ★☆☆ 아이템 1개를 130,000골드에 판매하셨습니다."));
    }

    @Test
    void 플로리스트_키워드_충돌_회피() {
        // "크리스텔라 오일"이 요리 식재료 '오일'로 새면 안 된다(요리보다 먼저 검사)
        assertEquals("요리", cat("올리브 오일"));
        assertEquals("플로리스트", cat("크리스텔라 오일 ★★"));
        // ★ 가 붙어도 해양전문가(★ 흡수)로 새면 안 된다
        assertEquals("플로리스트", cat("벨라로제 ★★★"));
        assertEquals("해양전문가", cat("★ 대왕문어"), "다른 ★ 아이템은 그대로 해양");
    }

    @Test
    void 전문가_분류() {
        assertEquals("채광전문가", cat("코룸 정동석"));
        assertEquals("채광전문가", cat("리프톤 주괴"));
        assertEquals("채광전문가", cat("탈리세르의 나뭇잎")); // 유물
        assertEquals("세공", cat("키론 오르골"));            // 귀중품은 채광 아님
        assertEquals("공룡", cat("회귀종 라프의 원혼"));
        assertEquals("사냥전문가", cat("평범한 표범"));
        assertEquals("요리", cat("딥 크림 빠네"));
        assertEquals("해양전문가", cat("성게 ★★"));
        assertEquals("공예품", cat("흑진주 시계"));          // 세공 '회중시계'와 충돌 금지
        assertEquals("재배전문가", cat("토마토 베이스"));
        assertEquals("바리스타", cat("커피"));
        assertEquals("무역", cat("무역"));
    }

    @Test
    void 노크틸라_신설() { // 사용자 확정 #1
        assertEquals("노크틸라", cat("정제된 루미디아의 조각"));
        assertEquals("노크틸라", cat("완성된 격파석"));
        assertEquals("노크틸라", cat("안정된 수호석"));
        assertEquals("노크틸라", cat("레어 등급 룬"));
        assertEquals("노크틸라", cat("정제된 선홍의 균사"));
        assertEquals("노크틸라", cat("인피니티 에테르"));
        // 채광 보물 '카르세나의 룬'은 노크틸라 룬과 혼동 금지
        assertEquals("채광전문가", cat("카르세나의 룬"));
    }

    @Test
    void 낚시대회는_배낚시와_별개() { // 사용자 확정 #2
        assertEquals("낚시대회", cat("신화 계곡 물고기"));
        assertEquals("낚시대회", cat("노멀 바다 생물"));
        assertEquals("낚시대회", cat("하급 파도 결정석"));
        assertEquals("낚시대회", cat("오로라 조각"));
        // 세레니티 해역 물고기는 배 낚시 유지
        assertEquals("배 낚시", cat("심해 해역 물고기"));
    }

    @Test
    void 배낚시_부산물과_쓰레기_분리() { // 사용자 확정 #3→#4
        assertEquals("배 낚시", cat("심해의 고철"));
        assertEquals("배 낚시", cat("해저 열수구 코어"));
        assertEquals("배 낚시", cat("영롱한 티타늄 광석")); // '광석'이지만 채광 아님
        assertEquals("배 낚시", cat("전설 열쇠 조각"));
        assertEquals("배 낚시", cat("녹슨 바다 상자"));
        // 쓰레기는 재활용품 가공 → 해양전문가
        assertEquals("해양전문가", cat("통조림"));
        assertEquals("해양전문가", cat("플라스틱 재활용품"));
        // 야생·일반 열쇠는 배 낚시 아님
        assertEquals("판매", cat("음산한 열쇠"));
        assertEquals("판매", cat("상자 잠금 해제 열쇠"));
    }

    @Test
    void 보물은_유물과_같이_채광() { // 사용자 확정 #5
        assertEquals("채광전문가", cat("에르칼의 장갑"));
        assertEquals("채광전문가", cat("아르데온의 반지"));
    }

    @Test
    void 물고기회는_연금() { // 사용자 확정 #7
        assertEquals("연금", cat("도미 회"));
        assertEquals("연금", cat("깐 새우"));
        // 낱말 '회' 오염 금지: 회중시계(세공)·회귀종 원혼(공룡)
        assertEquals("세공", cat("세피아 회중시계"));
        assertEquals("공룡", cat("회귀종 라프의 원혼"));
    }

    @Test
    void 각인석은_사냥전문가() { // 사용자 확정 #8
        assertEquals("사냥전문가", cat("수상한 각인석 조각"));
        assertEquals("사냥전문가", cat("정교한 각인석"));
    }

    @Test
    void 강화횃불은_채광_바닐라횃불은_아님() {
        // 2026-07-28: 강화 횃불은 주괴 제작 소모품이라 채광 활동 산출물, 바닐라 횃불은 아님
        assertEquals("채광전문가", cat("강화 횃불"));
        assertEquals("판매", cat("횃불"));
    }

    @Test
    void 도구_장비는_전문가수익이_아님() {
        // 2026-07-28 제보: 어선 수리 키트가 '어선' 때문에 배 낚시로 잡힘.
        // 도구는 낚시 산출물이 아니므로 전문가에서 빠져야 한다.
        assertEquals("판매", SaleCategory.of("어선 수리 키트", "[LEGENDARY] 어선 수리 키트"));
        // 바닐라 방어구가 해양 쓰레기('신발')·사냥('가죽')으로 새지 않아야 함
        assertEquals("판매", cat("가죽 신발"));
        assertEquals("판매", cat("다이아몬드 신발"));
        // 진짜 어획물·쓰레기는 그대로
        assertEquals("배 낚시", cat("심해 해역 물고기"));
        assertEquals("해양전문가", cat("신발"));  // 낚시 쓰레기 '신발'
    }

    @Test
    void 각인석_효과명이_채광키워드와_겹쳐도_사냥전문가() {
        // 2026-07-27 제보: "투박한 광채 탐색 각인석"이 '광채'(광채 원석) 때문에 채광전문가로 샘
        assertEquals("사냥전문가", cat("투박한 광채 탐색 각인석"));
        assertEquals("사냥전문가", cat("단정한 주화 탐색 각인석"));
        // 진짜 광채 원석은 그대로 채광
        assertEquals("채광전문가", cat("오팔 광채 원석"));
    }

    @Test
    void 야생고기는_미분류_식재료는_요리() { // 사용자 확정 #9·#10
        assertEquals("판매", cat("익힌 돼지 삼겹살"));
        assertEquals("판매", cat("좀비의 심장"));
        assertEquals("판매", cat("익힌 새우"));
        assertEquals("요리", cat("설탕 큐브"));
        assertEquals("요리", cat("밀가루 반죽"));
        assertEquals("요리", cat("버터 조각"));
        // 삼겹살 요리는 찌개/찜으로 잡힘
        assertEquals("요리", cat("삼겹살 토마토 찌개"));
        assertEquals("요리", cat("허브 삼겹살 찜"));
    }

    @Test
    void 판매처는_이_분류가_결정하지_않는다() {
        // 2026-07-28 투표 B안: 유저상점 판매는 품목과 무관하게 TransferClassifier 가
        // "유저상점"으로 확정한다. 여기(SaleCategory)는 NPC·무역 판매의 활동만 판정.
        assertEquals("채광전문가", SaleCategory.of("중급 라이프스톤", "[RARE] 중급 라이프스톤"));
        assertEquals("채광전문가", cat("코룸 주괴"));
        assertEquals("해양전문가", cat("문어"));
        assertEquals("판매", cat("골든티켓")); // 활동 특정 불가
    }
}
