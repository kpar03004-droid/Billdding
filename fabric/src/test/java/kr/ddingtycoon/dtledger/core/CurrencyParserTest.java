package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 코딩계획서 §6 확정 정규식 오프라인 검증(서버 불필요).
 * 확보된 실제 원문 그대로 사용.
 */
class CurrencyParserTest {

    private final CurrencyParser parser = CurrencyParser.createDefault();

    @Test
    void 판매_수입() {
        TradeSignal s = parser.parse("[RARE] 평범한 표범 아이템 1개를 18,314골드에 판매하셨습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(18314, s.amount);
        assertEquals(1, s.qty);
        assertEquals("평범한 표범", s.label);
        assertEquals(1, s.expectedSign());
    }

    @Test
    void 정동석_판매_등급태그_ROOKIE() {
        TradeSignal s = parser.parse("[ROOKIE] 코룸 정동석 아이템 19개를 182,400골드에 판매하셨습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(182400, s.amount);
        assertEquals(19, s.qty);
        assertEquals("코룸 정동석", s.label);
    }

    @Test
    void 정동석_변종_리프톤_세렌트() {
        TradeSignal a = parser.parse("[RARE] 리프톤 정동석 아이템 5개를 50,000골드에 판매하셨습니다.");
        assertEquals("리프톤 정동석", a.label);
        TradeSignal b = parser.parse("세렌트 정동석 아이템 3개를 30,000골드에 판매하셨습니다.");
        assertEquals("세렌트 정동석", b.label);
        assertEquals(TradeSignal.Type.SALE, b.type);
    }

    @Test
    void 플리마켓_슬롯_재고_빼내기는_무시() {
        // 아이템 수량 메시지 — 골드 거래 아님. null 이어야 함(오분류 금지).
        assertNull(parser.parse("플리마켓 슬롯 재고를 640만큼 빼냈습니다."));
        assertNull(parser.parse("플리마켓 슬롯 재고를 6,400개 만큼 빼냈습니다."));
    }

    @Test
    void 요리_판매_띄어쓰기_품목() {
        // 요리사 요리 판매 — 표준 "판매하셨습니다" 형식, 품목명에 공백 포함
        TradeSignal s = parser.parse("[EPIC] 딥 크림 빠네 아이템 750개를 3,490,500골드에 판매하셨습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(3490500, s.amount);
        assertEquals(750, s.qty);
        assertEquals("딥 크림 빠네", s.label);
    }

    @Test
    void 바리스타_커피_판매_완료_및_일부판매() {
        TradeSignal full = parser.parse("커피 판매를 완료했습니다. 670,824 골드를 얻었습니다.");
        assertNotNull(full);
        assertEquals(TradeSignal.Type.SALE, full.type);
        assertEquals(670824, full.amount);
        assertEquals("커피", full.label);

        TradeSignal partial = parser.parse("커피를 일부 판매했습니다. 169,884 골드를 얻었습니다.");
        assertNotNull(partial);
        assertEquals(TradeSignal.Type.SALE, partial.type);
        assertEquals(169884, partial.amount);
        assertEquals("커피", partial.label);
    }

    @Test
    void 강화_성공_실패_모두_지출신호() {
        // 실측 문구(2026-07-21): 무기 이름 대신 일반 명칭 사용, 끝에 !/. 혼용
        TradeSignal a = parser.parse("낚싯대 강화에 성공했습니다!");
        assertNotNull(a);
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, a.type);
        assertTrue(a.amountFromDelta);
        assertEquals(-1, a.expectedSign());
        assertTrue(a.label.contains("낚싯대 강화 성공"));

        TradeSignal b = parser.parse("장비 강화에 성공했습니다.");
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, b.type);
        assertTrue(b.label.contains("장비 강화 성공"));

        // 실패도 골드+재료 소모 → 지출 신호로 잡힘. 어미 '했/하였' 혼용 모두 매칭.
        //   (2026-07-21 실측: 강화 실패는 "실패했습니다", 각인 실패는 "실패하였습니다")
        TradeSignal c = parser.parse("장비 강화에 실패했습니다.");
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, c.type);
        assertTrue(c.label.contains("장비 강화 실패"));

        TradeSignal d = parser.parse("장비 강화에 실패하였습니다.");
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, d.type);
        assertTrue(d.label.contains("장비 강화 실패"));
    }

    @Test
    void 각인_성공_실패_모두_지출신호() {
        TradeSignal fail = parser.parse("장비 각인에 실패하였습니다.");
        assertNotNull(fail);
        assertEquals(TradeSignal.Type.ENGRAVE, fail.type);
        assertTrue(fail.amountFromDelta);
        assertEquals(-1, fail.expectedSign());
        assertTrue(fail.label.contains("장비 각인 실패"));

        // 각인 실패 어미 '했' 변형도 매칭
        TradeSignal fail2 = parser.parse("장비 각인에 실패했습니다.");
        assertEquals(TradeSignal.Type.ENGRAVE, fail2.type);

        TradeSignal ok = parser.parse("장비 각인에 성공했습니다.");
        assertEquals(TradeSignal.Type.ENGRAVE, ok.type);
        assertTrue(ok.label.contains("장비 각인 성공"));

        // 2026-07-29 실측: 성공은 실패와 문구 구조가 완전히 다름(제보: 성공만 지출 미집계)
        TradeSignal realOk = parser.parse("아이템에 각인을 성공적으로 적용했습니다!");
        assertNotNull(realOk);
        assertEquals(TradeSignal.Type.ENGRAVE, realOk.type);
        assertTrue(realOk.amountFromDelta, "금액은 잔고 변동으로 산정");
        assertEquals(-1, realOk.expectedSign());
        assertEquals("장비 각인 성공", realOk.label);
    }

    @Test
    void 도구_수리는_지출신호() {
        // 2026-07-30 실측: "장비 내구도가 모두 수리되었습니다."
        // 수리 창엔 "수리 소모 골드 : 5,286골드"가 뜨지만 채팅엔 금액이 없고 손상도마다 달라짐
        TradeSignal s = parser.parse("장비 내구도가 모두 수리되었습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.TOOL_REPAIR, s.type);
        assertTrue(s.amountFromDelta, "금액은 잔고 변동으로 산정");
        assertEquals(-1, s.expectedSign());
        assertEquals("도구 수리", s.label);

        // "모두" 없는 변형도 인식
        assertEquals(TradeSignal.Type.TOOL_REPAIR,
                parser.parse("장비 내구도가 수리되었습니다").type);
    }

    @Test
    void 각인석_조사는_성공실패_모두_고정_3만원_지출() {
        // 2026-07-30 실측: 성공·실패 무관하게 매번 30,000골드 소모(창 "조사 비용 : 30,000골드")
        TradeSignal ok = parser.parse("수상한 각인석 조사에 성공했습니다!");
        assertNotNull(ok);
        assertEquals(TradeSignal.Type.ENGRAVE_INVESTIGATE, ok.type);
        assertEquals(30_000, ok.amount, "금액이 확정값이라 ΔG 없이 기록");
        assertFalse(ok.amountFromDelta);
        assertEquals(-1, ok.expectedSign());
        assertTrue(ok.label.contains("성공"));

        TradeSignal fail = parser.parse("수상한 각인석 조사에 실패하여 조각을 얻었습니다.");
        assertNotNull(fail);
        assertEquals(TradeSignal.Type.ENGRAVE_INVESTIGATE, fail.type);
        assertEquals(30_000, fail.amount, "실패해도 비용은 나감");
        assertTrue(fail.label.contains("실패"));

        // 장비 각인(rule 9)과 섞이면 안 됨
        assertEquals(TradeSignal.Type.ENGRAVE, parser.parse("장비 각인에 실패하였습니다.").type);
    }

    @Test
    void 마을_투자는_지출이고_금액은_메시지에서_읽는다() {
        // 2026-08-02 실측 완료문
        TradeSignal s = parser.parse("50,000골드를 마을에 투자하였습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.VILLAGE_INVEST, s.type);
        assertEquals(50_000, s.amount);
        assertFalse(s.amountFromDelta, "금액이 본문에 있어 ΔG 불필요");
        assertEquals(-1, s.expectedSign());
        assertEquals("마을 투자", s.label);
    }

    @Test
    void 마을_투자_금액은_매번_달라도_그대로_읽는다() {
        // 사용자 확인(2026-08-02): 5만이 최소일 뿐 넣는 금액은 매번 다름 → 고정값으로 박으면 안 됨
        assertEquals(50_000, parser.parse("50,000골드를 마을에 투자하였습니다.").amount);
        assertEquals(123_456, parser.parse("123,456골드를 마을에 투자하였습니다.").amount);
        assertEquals(1_000_000, parser.parse("1,000,000골드를 마을에 투자하였습니다.").amount);
        assertEquals(100_000_000L, parser.parse("100,000,000골드를 마을에 투자하였습니다.").amount, "1억");
        assertEquals(9_876_543_210L, parser.parse("9,876,543,210골드를 마을에 투자하였습니다.").amount,
                "int 범위(21억) 초과 — long 으로 받는지 확인");
        assertEquals(70_000, parser.parse("70000골드를 마을에 투자하였습니다.").amount, "쉼표 없는 표기");
        assertEquals(50_000, parser.parse("50,000 골드를 마을에 투자하였습니다.").amount, "숫자-골드 사이 공백");
    }

    @Test
    void 마을_투자_확인창은_잡지_않는다() {
        // 이 문구는 [동의]/[취소] 대화상자 — 아직 돈이 안 나갔다. 잡으면 이중계상 된다.
        assertNull(parser.parse("50,000골드를 투자하시겠습니까? 완료 후에는 투자한 골드를 회수할 수 없습니다."));
        assertNull(parser.parse("완료 후에는 투자한 골드를 회수할 수 없습니다."));
    }

    @Test
    void 남의_마을_투자는_무시한다() {
        assertNull(parser.parse("prettyman_님이 50,000골드를 마을에 투자하였습니다."));
        assertNull(parser.parse("[VIP] prettyman_님이 100,000골드를 마을에 투자하였습니다."));
    }

    @Test
    void 도구_품질회복도_지출신호() {
        // 2026-07-30 실측: "장비 품질이 +65 회복되어 205로 향상되었습니다." (창: 회복 비용 10000골드)
        // 메시지의 65·205 는 품질 수치이지 골드가 아니므로 금액으로 쓰면 안 된다.
        TradeSignal s = parser.parse("장비 품질이 +65 회복되어 205로 향상되었습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.QUALITY_RESTORE, s.type);
        assertTrue(s.amountFromDelta, "금액은 잔고 변동으로 산정");
        assertEquals(0, s.amount, "품질 수치를 금액으로 오인하면 안 됨");
        assertEquals(-1, s.expectedSign());
        assertEquals("품질 회복", s.label);

        // 내구도 수리와 유형이 섞이면 안 됨(비용 체계가 달라 합산 분할 시 문제)
        assertEquals(TradeSignal.Type.TOOL_REPAIR,
                parser.parse("장비 내구도가 모두 수리되었습니다.").type);
    }

    @Test
    void RPG_스킬강화_성공실패_지출신호() {
        // 실측(2026-07-21): "스킬 강화에 실패했습니다." — RPG 스킬 강화, 성공/실패 모두 골드 소모
        TradeSignal fail = parser.parse("스킬 강화에 실패했습니다.");
        assertNotNull(fail);
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, fail.type);
        assertTrue(fail.amountFromDelta);
        assertTrue(fail.label.contains("스킬 강화 실패"));

        TradeSignal ok = parser.parse("스킬 강화에 성공했습니다.");
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, ok.type);
        assertTrue(ok.label.contains("스킬 강화 성공"));
    }

    @Test
    void 전문가_스킬업그레이드_문구변형_모두인식() {
        // 2026-07-28 제보: 전문가 업그레이드 비용이 지출로 안 잡힘
        TradeSignal s = parser.parse("해양학개론 스킬 업그레이드에 성공했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SKILL_UPGRADE, s.type);
        assertTrue(s.amountFromDelta, "금액은 잔고 변동으로 산정");
        assertEquals(-1, s.expectedSign());
        assertEquals("해양학개론 스킬", s.label, "성공은 기존 표기 유지");

        // 어미 '하였' 변형·실패 문구도 지출(골드는 이미 소모됨)
        assertEquals(TradeSignal.Type.SKILL_UPGRADE,
                parser.parse("채광학개론 스킬 업그레이드에 성공하였습니다.").type);
        assertEquals(TradeSignal.Type.SKILL_UPGRADE,
                parser.parse("채광학개론 스킬 업그레이드에 실패했습니다").type);

        // RPG '스킬 강화'(rule 9)와 섞이면 안 됨
        assertEquals(TradeSignal.Type.WEAPON_ENHANCE, parser.parse("스킬 강화에 실패했습니다.").type);
    }

    @Test
    void 낚시대회_순위보상은_순위로_금액확정() {
        // 2026-07-28 실측(제보 #6b): 우편으로만 지급되고 수령 채팅이 없어 순위로 금액을 정함
        TradeSignal first = parser.parse(
                "타이니랜드 낚시 대회 26일 2회차 1위 보상 우편이 도착했습니다. [/우편함]에서 확인할 수 있습니다.");
        assertNotNull(first);
        assertEquals(TradeSignal.Type.CONTEST_PRIZE, first.type);
        assertEquals(500_000, first.amount);
        assertEquals(1, first.expectedSign());
        assertTrue(first.label.contains("1위"));

        // 등수당 1만원 감소 — 위키 표 검산값
        assertEquals(230_000, parser.parse("타이니랜드 낚시 대회 28위 보상 우편이 도착했습니다.").amount);
        assertEquals(210_000, parser.parse("타이니랜드 낚시 대회 30위 보상 우편이 도착했습니다.").amount);
        // 31위 이하는 골드 보상 없음 → 기록 안 함
        assertNull(parser.parse("타이니랜드 낚시 대회 31위 보상 우편이 도착했습니다."));
    }

    @Test
    void 품목명없는_판매완료는_육식동물_사냥전문가() {
        // 2026-07-27 실측(제보 #8): "판매를 완료했습니다. 20,145 골드를 얻었습니다." — 품목명 없음
        TradeSignal s = parser.parse("판매를 완료했습니다. 20,145 골드를 얻었습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(20_145, s.amount);
        assertEquals("사냥전문가", SaleCategory.of(s.label, s.raw));

        // 커피는 전용 문구(rule 63)가 먼저 잡아야 함 — 육식동물로 새면 안 됨
        TradeSignal coffee = parser.parse("커피 판매를 완료했습니다. 222,084 골드를 얻었습니다.");
        assertEquals("커피", coffee.label);
        assertEquals("바리스타", SaleCategory.of(coffee.label, coffee.raw));
    }

    @Test
    void NPC상점_구매는_유저상점과_구분() {
        // 2026-07-27 제보: 두리(NPC)에게 산 작업 램프가 "유저상점"으로 표기됨
        TradeSignal npc = parser.parse("[ROOKIE] 작업 램프 아이템 64개를 6,400,000골드에 구입하셨습니다.");
        assertNotNull(npc);
        assertEquals(TradeSignal.Type.NPC_SHOP_BUY, npc.type);
        assertEquals(6_400_000, npc.amount);
        assertEquals(64, npc.qty);
        // 유저상점 구매는 문구가 달라 그대로 USERSHOP_BUY
        assertEquals(TradeSignal.Type.USERSHOP_BUY,
                parser.parse("자수정 블록 아이템을 6,400골드에 구매하였습니다.").type);
    }

    @Test
    void 타인_마을은행_방송은_아이콘_태그가_붙어도_무시() {
        // 2026-07-27 제보: 마을 은행은 마을원 공용 → 남의 입출금 방송이 내 거래로 오계상됨.
        // 선두 아이콘/색코드/[태그] 뒤의 "{닉}님이" 도 확실히 걸러야 함.
        assertNull(parser.parse("❗ 홍길동님이 마을 은행에 1,000,000골드를 입금했습니다."));
        assertNull(parser.parse("[마을] 홍길동님이 마을 은행에서 500,000골드를 출금했습니다."));
        assertNull(parser.parse("홍길동님께서 마을 은행에서 500,000골드를 출금했습니다."));
        // 내 입출금은 여전히 잡혀야 함
        TradeSignal mine = parser.parse("마을 은행에 1,000,000골드를 입금했습니다.");
        assertNotNull(mine);
        assertEquals(TradeSignal.Type.BANK_DEPOSIT, mine.type);
        // 직거래 송금("님에게")은 '님이' 규칙에 안 걸려야 함
        assertEquals(TradeSignal.Type.USER_TRANSFER_IN,
                parser.parse("홍길동님에게 10,000골드를 받았습니다.").type);
    }

    @Test
    void 의뢰완료는_임시비활성으로_미파싱() {
        // 🔧 2026-07-25 배포용 임시 비활성 — 의뢰 보상이 무관한 +ΔG(송금·판매)를 훔치는 버그.
        //    다음 주 규칙 11 재활성화 시 이 테스트를 QUEST_REWARD 검증으로 뒤집을 것.
        assertNull(parser.parse("❕ 의뢰를 완료하였습니다"));
        assertNull(parser.parse("의뢰를 완료했습니다"));
    }

    @Test
    void 강화각인_무관한메시지는_무시() {
        // 각인 재료 안내 프롬프트는 골드거래 아님 → null
        assertNull(parser.parse("각인 재료 슬롯에 각인 재료를 넣어주세요."));
        // 전문가 스킬 '업그레이드'는 별도 규칙(SKILL_UPGRADE) — RPG '스킬 강화'와 문구 달라 충돌 없음
        TradeSignal skill = parser.parse("해양학개론 스킬 업그레이드에 성공했습니다.");
        assertEquals(TradeSignal.Type.SKILL_UPGRADE, skill.type);
    }

    @Test
    void 금고잔액_스냅샷_재동기화_파싱() {
        // "/플리마켓 금고" 결과 → 실제 잔액 반환(재동기화용), 레코드 아님
        assertEquals(12_635_710L, CurrencyParser.parseVaultBalance("플리마켓 금고 잔액: 12,635,710 골드"));
        // 앞에 아이콘/색코드가 붙어도 매칭
        assertEquals(500_000L, CurrencyParser.parseVaultBalance("⚠ 플리마켓 금고 잔액: 500,000 골드"));
        // 무관한 메시지는 -1
        assertEquals(-1L, CurrencyParser.parseVaultBalance("플리마켓 금고에 3,000골드를 입금했습니다."));
        assertEquals(-1L, CurrencyParser.parseVaultBalance("아무 메시지"));
        // 금고 잔액 스냅샷은 거래 신호로는 잡히지 않아야 함(입금/출금 규칙과 혼동 금지)
        assertNull(parser.parse("플리마켓 금고 잔액: 12,635,710 골드"));
    }

    @Test
    void 무역_판매_하였습니다형식() {
        TradeSignal s = parser.parse("무역 아이템을 45,780골드에 판매하였습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(45780, s.amount);
        assertEquals("무역", s.label);
        // 구매(하였습니다)와 혼동 없이 구분
        assertEquals(TradeSignal.Type.USERSHOP_BUY,
                parser.parse("자수정 블록 아이템을 6,400골드에 구매하였습니다.").type);
    }

    @Test
    void 리프톤_주괴_판매() {
        TradeSignal s = parser.parse("[NORMAL] 리프톤 주괴 아이템 1개를 3,938골드에 판매하셨습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(3938, s.amount);
        assertEquals(1, s.qty);
        assertEquals("리프톤 주괴", s.label);
    }

    @Test
    void 전문가_스킬_업그레이드_금액은_ΔG() {
        TradeSignal s = parser.parse("해양학개론 스킬 업그레이드에 성공했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SKILL_UPGRADE, s.type);
        assertTrue(s.amountFromDelta);        // 금액은 ΔG 에서
        assertEquals(0, s.amount);            // 메시지엔 금액 없음
        assertEquals("해양학개론 스킬", s.label);
        assertEquals(-1, s.expectedSign());   // 골드 소모
    }

    @Test
    void 라벨의_장식아이콘_제거_한글이름_보존() {
        // 사냥꾼 전리품 판매 메시지에 서버 리소스팩 전용 장식 아이콘(트로피 등)이 이름 앞에
        // 붙어 오는 경우, 모드 자체 폰트에는 없는 글리프라 깨진 네모로 뜨는 문제 회귀 방지.
        TradeSignal s = parser.parse("[LEGENDARY] ☆⑥❗ ♪ 회귀종 라프의 원혼 아이템 4개를 3,000,000골드에 판매하셨습니다.");
        assertNotNull(s);
        assertEquals("회귀종 라프의 원혼", s.label);
        assertEquals(3000000, s.amount);
        assertEquals(4, s.qty);
    }

    @Test
    void 등급_글리프가_이름_뒤에_붙어도_제거된다() {
        // 2026-08-07 제보(엑셀에 한자가 찍힘): 서버 리소스팩이 등급 아이콘으로 쓰는
        // 算·山·産 이 품목명 "뒤"에 붙어 온다. 선두 정리로는 안 걸려 그대로 남아 있었다.
        assertEquals("해구의 파동 코어",
                parser.parse("해구의 파동 코어 山 아이템을 1,649,580골드에 판매하였습니다.").label);
        assertEquals("영생의 아쿠티스",
                parser.parse("영생의 아쿠티스 産 아이템을 2,857,182골드에 판매하였습니다.").label);
        assertEquals("아쿠아 펄스 파편",
                parser.parse("아쿠아 펄스 파편 算 아이템을 3,831,705골드에 판매하였습니다.").label);
    }

    @Test
    void 이_음절_등급아이콘도_제거된다() {
        // ꀂ(U+A002) 등 — 세공품 등급 표시. "ꀂ 키론 오르골" 형태로 붙어 온다.
        assertEquals("키론 오르골",
                parser.parse("ꀂ 키론 오르골 아이템을 500,000골드에 판매하였습니다.").label);
    }

    @Test
    void 다른_글자에_붙은_한자는_보존한다() {
        // 算·山·産 은 정상 한자라 통째로 지우면 안 된다 — 공백으로 떨어진 단독 글자만 제거한다.
        // (이름 맨 앞의 비한글 뭉치는 예전부터 선두 정리가 걷어내므로 여기선 중간에 둔다.)
        assertEquals("영묘한 山水화 조각",
                parser.parse("영묘한 山水화 조각 아이템을 1,000골드에 판매하였습니다.").label);
        assertEquals("계산기 部品",
                parser.parse("계산기 部品 아이템을 1,000골드에 판매하였습니다.").label);
    }

    @Test
    void 귀중품_판매_NPC형식() {
        TradeSignal s = parser.parse("귀중품을 판매하여 286,028골드를 획득했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.SALE, s.type);
        assertEquals(286028, s.amount);
        assertEquals("귀중품", s.label);
        assertEquals(1, s.expectedSign());
    }

    @Test
    void 유저상점_구매_지출() {
        TradeSignal s = parser.parse("자수정 블록 아이템을 6,400골드에 구매하였습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.USERSHOP_BUY, s.type);
        assertEquals(6400, s.amount);
        assertEquals("자수정 블록", s.label);
        assertEquals(-1, s.expectedSign());
    }

    @Test
    void 플리마켓_구매_지출_금고아님() {
        TradeSignal s = parser.parse("플리마켓에서 99개를 100골드에 구매했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_BUY, s.type);
        assertEquals(100, s.amount);   // 총액
        assertEquals(99, s.qty);
        assertEquals(-1, s.expectedSign());
    }

    @Test
    void 은행_입금_수수료_분리() {
        TradeSignal s = parser.parse("마을 은행에 950,000 골드를 입금했습니다. (수수료: 50,000 골드)");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.BANK_DEPOSIT, s.type);
        assertEquals(950000, s.amount);
        assertEquals(50000, s.fee);
        assertEquals(1000000, s.expectedMagnitude()); // ΔG 교차검증 = 입금+수수료
        assertEquals(-1, s.expectedSign());
    }

    @Test
    void 은행_입금_수수료_없음() {
        TradeSignal s = parser.parse("마을 은행에 100,000 골드를 입금했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.BANK_DEPOSIT, s.type);
        assertEquals(100000, s.amount);
        assertEquals(0, s.fee);
    }

    @Test
    void 플리마켓_금고_입금_이체() {
        TradeSignal s = parser.parse("플리마켓 금고에 30,000 골드를 입금했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_VAULT_DEPOSIT, s.type);
        assertEquals(30000, s.amount);
    }

    @Test
    void 플리마켓_금고_출금_이체() {
        TradeSignal s = parser.parse("플리마켓 금고에서 30,000 골드를 출금했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_VAULT_WITHDRAW, s.type);
        assertEquals(30000, s.amount);
        assertEquals(1, s.expectedSign());
    }

    @Test
    void 은행_출금_추정규칙() {
        TradeSignal s = parser.parse("마을 은행에서 200,000 골드를 출금했습니다.");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.BANK_WITHDRAW, s.type);
        assertEquals(200000, s.amount);
    }

    @Test
    void 금고_구매_우선순위_구분() {
        // "플리마켓 금고" 이체가 "플리마켓에서 구매"로 오분류되지 않아야 함
        TradeSignal vault = parser.parse("플리마켓 금고에 5,000 골드를 입금했습니다.");
        assertEquals(TradeSignal.Type.FLEA_VAULT_DEPOSIT, vault.type);
    }

    @Test
    void 내플리마켓_판매성사_금고유입_수입() {
        TradeSignal s = parser.parse("[플리마켓] 플리마켓에서 유리 99개가 3,000골드에 구매되었습니다. (구매자: prettyman_)");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_SALE, s.type);
        assertEquals(3000, s.amount);
        assertEquals(99, s.qty);
        assertEquals("유리", s.label);
        assertEquals(0, s.expectedSign()); // 금고 유입 — 잔고 ΔG 없음이 정상
    }

    @Test
    void 내플리마켓_매수체결_금고유출_지출() {
        TradeSignal s = parser.parse("[플리마켓] 플리마켓에서 유리 99개를 3,000골드에 판매받았습니다. (판매자: prettyman_)");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_ORDER_FILLED, s.type);
        assertEquals(3000, s.amount);
        assertEquals(99, s.qty);
        assertEquals(0, s.expectedSign());
    }

    @Test
    void 거래창_받은_보낸_골드() {
        TradeSignal in = parser.parse("├ 받은 골드 : 100골드");
        assertEquals(TradeSignal.Type.USER_TRANSFER_IN, in.type);
        assertEquals(100, in.amount);

        TradeSignal out = parser.parse("└ 보낸 골드 : 10,000골드 (수수료 500골드)");
        assertEquals(TradeSignal.Type.USER_TRANSFER_OUT, out.type);
        assertEquals(10000, out.amount);
        assertEquals(500, out.fee);
        assertEquals(10500, out.expectedMagnitude());

        // 아이템 줄은 골드 규칙에 안 걸려야 함
        assertNull(parser.parse("├ 보냄 : 자수정 블록 (1개)"));
    }

    @Test
    void 직접송금_보냄_받음() {
        TradeSignal out = parser.parse("prettyman_님에게 10,000골드를 보냈습니다. (수수료: 500 골드)");
        assertEquals(TradeSignal.Type.USER_TRANSFER_OUT, out.type);
        assertEquals(10000, out.amount);
        assertEquals(500, out.fee);
        assertEquals(-1, out.expectedSign());

        TradeSignal in = parser.parse("prettyman_님에게 10,000골드를 받았습니다.");
        assertEquals(TradeSignal.Type.USER_TRANSFER_IN, in.type);
        assertEquals(10000, in.amount);
        assertEquals(1, in.expectedSign());
    }

    @Test
    void 은행_출금_내것은_집계_방송은_무시() {
        TradeSignal mine = parser.parse("마을 은행에서 950,000 골드를 출금했습니다.");
        assertNotNull(mine);
        assertEquals(TradeSignal.Type.BANK_WITHDRAW, mine.type);
        assertEquals(950000, mine.amount);
        // 방송(주어 있음)은 중복 방지 위해 무시
        assertNull(parser.parse("아카님이 마을 은행에서 950,000골드를 출금했습니다."));
    }

    @Test
    void 앞_아이콘_문자가_있어도_매칭() {
        // 서버 메시지 앞 아이콘/색코드가 붙어도 find 로 잡혀야 함
        TradeSignal s = parser.parse("❗ [플리마켓] 플리마켓에서 유리 99개를 3,000골드에 판매받았습니다. (판매자: Hyuhana)");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_ORDER_FILLED, s.type);
        assertEquals(3000, s.amount);
        assertEquals(99, s.qty);
        assertEquals("유리", s.label);
    }

    @Test
    void 겹친_메시지_N배_승수() {
        // "[2]" → 2배 집계
        TradeSignal s = parser.parse("[플리마켓] 플리마켓에서 유리 99개를 3,000골드에 판매받았습니다. (판매자: Hyuhana) [2]");
        assertNotNull(s);
        assertEquals(TradeSignal.Type.FLEA_ORDER_FILLED, s.type);
        assertEquals(6000, s.amount);   // 3,000 × 2
        assertEquals(198, s.qty);       // 99 × 2
        // 은행 입금 수수료도 함께 배수
        TradeSignal dep = parser.parse("마을 은행에 100,000 골드를 입금했습니다. (수수료: 5,000 골드) [3]");
        assertEquals(300000, dep.amount);
        assertEquals(15000, dep.fee);
        assertEquals(315000, dep.expectedMagnitude()); // (100,000+5,000)×3
        // 재고 빼내기 [2]는 여전히 무시
        assertNull(parser.parse("플리마켓 슬롯 재고를 640만큼 빼냈습니다. [2]"));
    }

    @Test
    void 타인_거래_메시지는_무시() {
        // 주어(닉네임)가 있는 거래 문장은 배제 (계획서 §3)
        assertNull(parser.parse("우석님이 표범 아이템 1개를 100골드에 판매하셨습니다."));
        assertNull(parser.parse("Steve가 자수정 블록 아이템을 6,400골드에 구매하였습니다."));
        // 내 메시지는 여전히 정상 파싱
        assertNotNull(parser.parse("코룸 정동석 아이템 19개를 182,400골드에 판매하셨습니다."));
    }

    @Test
    void 무관한_메시지는_null() {
        assertNull(parser.parse("안녕하세요 반갑습니다"));
        assertNull(parser.parse(""));
        assertNull(parser.parse(null));
    }
}
