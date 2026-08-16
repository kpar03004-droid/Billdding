package kr.ddingtycoon.dtledger.core;

/**
 * 서버(띵타) 커스텀 아이템 아이콘 매핑 — 품목별(match) + 카테고리 대표(forCategory).
 *
 * 리소스팩이 base item + custom_model_data(float)로 커스텀 텍스처를 입히므로, (baseItem, cmd)
 * 값만 알면 MC가 커스텀 아이콘을 렌더한다. common(MC 의존성 0)에는 데이터만 — ItemStack 구성·렌더는
 * 각 로더 UI(fabric ItemIcons / neoforge NeoItemIcons)가 담당.
 *
 * 값 출처: 최신 서버 팩(2026-07-22, .dawn feather 프로필 downloads) items/*.json 전수 파싱
 * + 인게임 `/빌띵 item` 덤프 검증. 전체 매칭표 문서: dtledger/docs/아이콘_매칭표.md
 * (추정 매핑 포함 — 사용자 검수 후 이 파일에서 수정).
 *
 * 판정 순서: 특수 핸들러(연금 티어×속성 → 어패류 성급 → 사냥 등급 → 공룡 등급 → 파도 결정석 등급)
 * → ENTRIES 순차(contains, 선언 순서가 충돌 해소: "흑진주 시계" 먼저 등) → null(카테고리 대표로).
 */
public final class CustomItemIcon {
    private CustomItemIcon() {}

    /** base item id + custom_model_data float. */
    public record Icon(String baseItem, float cmd) {}

    private static final String PAPER = "minecraft:paper";
    private static final String BOOK = "minecraft:enchanted_book"; // 커피 음료
    private static final String BEEF = "minecraft:cooked_beef";    // 요리 완성품
    private static Icon paper(float cmd) { return new Icon(PAPER, cmd); }

    // ── 품목별 단순 매칭(키워드 contains, 순서 = 우선순위) ──────────────────
    //    {키워드, base, cmd}. 충돌 주의 순서: 흑진주 시계>흑진주, 회중시계>세피아,
    //    치즈케이크>치즈, 심층암>조약돌, "코룸 주괴">코룸, 각인석 조각>각인석,
    //    정제된 루미디아>루미디아, 심연의 오로라/오로라 조각/오로라 아인슈페너(전체구 구분).
    private static final Object[][] ENTRIES = {
            // 공예품(진주 공예품) — 전부 확정(팩 모델명 명확)
            {"흑진주 시계", PAPER, 2027023f},
            {"조개껍데기 브로치", PAPER, 2027014f}, {"브로치", PAPER, 2027014f},
            {"푸른 향수병", PAPER, 2027017f}, {"향수병", PAPER, 2027017f},
            {"손거울", PAPER, 2027013f},
            {"헤어핀", PAPER, 2027007f},
            {"자개 부채", PAPER, 2027012f}, {"부채", PAPER, 2027012f},
            // 진주 6종
            {"노란빛 진주", PAPER, 2027003f},
            {"푸른빛 진주", PAPER, 2027016f},
            {"청록빛 진주", PAPER, 2027015f},
            {"분홍빛 진주", PAPER, 2027006f},
            {"보라빛 진주", PAPER, 2027005f},
            {"흑진주", PAPER, 2027022f},
            // 세공 귀중품(luxury1~9, 등급 _3 대표) — 회중시계=luxury8_3 실측, 나머지 카탈로그 순서 추정
            {"회중시계", PAPER, 2046031f},
            {"오르골", PAPER, 2046003f},
            {"축음기", PAPER, 2046007f},
            {"만년필", PAPER, 2046011f},
            {"만화경", PAPER, 2046015f},
            {"라이터", PAPER, 2046019f},
            {"이어커프", PAPER, 2046023f},
            {"천체관측기", PAPER, 2046027f},
            {"단안경", PAPER, 2046035f},
            // 연금 t3 명명품(고유명사) — hand/spine/shard/wing/feather/core 는 이름 대응 확정급
            {"나우틸러스", PAPER, 2027028f},
            {"무저", PAPER, 2027060f},
            {"척추", PAPER, 2027060f},
            {"펄스 파편", PAPER, 2027029f},
            {"청해룡", PAPER, 2027024f},
            {"리바이던", PAPER, 2027025f},
            {"해구", PAPER, 2027027f},
            {"침묵", PAPER, 2027026f},
            {"비약", PAPER, 2027026f},
            {"아쿠티스", PAPER, 2027061f},   // 추정(t3_1star_coin)
            {"크라켄", PAPER, 2027062f},     // 추정(t3_1star_powder)
            {"광란체", PAPER, 2027062f},
            {"희석액", PAPER, 2027064f},
            // 연금 회(생선 가공) — fish_piece 대응
            {"도미 회", PAPER, 2019012f},
            {"청어 회", PAPER, 2019010f},
            {"금붕어 회", PAPER, 2019009f},
            {"농어 회", PAPER, 2019011f},
            {"깐 새우", PAPER, 2019013f},
            // 야생 물고기(회 아님 — 유저상점 판매). base=cooked_beef. "회" 항목이 위라 우선.
            {"농어", BEEF, 2019001f},
            {"도미", BEEF, 2019003f},
            {"청어", BEEF, 2019004f},
            {"금붕어", BEEF, 2019005f},
            {"새우", BEEF, 2019002f},
            // 채광 — 정동석·라이프스톤·주괴·원석 이름 (실측 확정)
            {"세렌트 정동석", PAPER, 2045001f},
            {"리프톤 정동석", PAPER, 2045002f},
            {"코룸 정동석", PAPER, 2045003f},
            {"상급 라이프스톤", PAPER, 2006026f},
            {"중급 라이프스톤", PAPER, 2006025f},
            {"하급 라이프스톤", PAPER, 2006024f}, {"라이프스톤", PAPER, 2006024f},
            // 유물(mining_relic 1~5) · 보물(treasure_relic 1~5) — 순서대로(2028001~2028010)
            {"탈리세르", PAPER, 2028001f},
            {"데르무스", PAPER, 2028002f},
            {"카이로스카", PAPER, 2028003f},
            {"실파드라", PAPER, 2028004f},
            {"아스트라곤", PAPER, 2028005f},
            {"에르칼", PAPER, 2028006f},
            {"이그논", PAPER, 2028007f},
            {"카르세나", PAPER, 2028008f},
            {"실바르", PAPER, 2028009f},
            {"아르데온", PAPER, 2028010f},
            {"코룸 주괴", PAPER, 2006004f},
            {"리프톤 주괴", PAPER, 2006006f},
            {"세렌트 주괴", PAPER, 2006010f},
            {"코룸", PAPER, 2006003f},
            {"리프톤", PAPER, 2006005f},
            {"세렌트", PAPER, 2006009f},
            {"강화 횃불", PAPER, 2006020f}, {"횃불", PAPER, 2006020f},
            {"심층암", PAPER, 2006022f},
            {"조약돌", PAPER, 2006023f},
            {"어빌리티", PAPER, 2006040f},
            {"오팔", PAPER, 2037010f},
            {"아다만티움", PAPER, 2037011f},
            {"바이올렛", PAPER, 2037012f},
            {"토파즈", PAPER, 2038006f},    // 미감정 토파즈 보석함(추정)
            // 채광 보석(gem1~9, 등급 _1 대표) — luxury 와 같은 9종 순서 추정
            {"키론", PAPER, 2045004f},
            {"테라온", PAPER, 2045008f},
            {"실바니움", PAPER, 2045012f},
            {"라온", PAPER, 2045016f},
            {"제피르", PAPER, 2045020f},
            {"아스트랄", PAPER, 2045024f},
            {"넬트", PAPER, 2045028f},
            {"세피아", PAPER, 2045032f},
            {"피로시아", PAPER, 2045036f},
            // 사냥 — 각인석·초식 전리품·영혼(영혼 먼저: "사슴의 영혼" vs "사슴 뿔")
            {"각인석 조각", PAPER, 2030023f},
            {"각인석", PAPER, 2030022f},
            {"사슴의 영혼", PAPER, 2030014f}, {"미어캣의 영혼", PAPER, 2030020f},
            {"기린의 영혼", PAPER, 2030012f}, {"코끼리의 영혼", PAPER, 2030015f},
            {"하마의 영혼", PAPER, 2030010f}, {"플라밍고의 영혼", PAPER, 2030009f},
            {"칠면조의 영혼", PAPER, 2030011f}, {"곰의 영혼", PAPER, 2030013f},
            {"사슴", PAPER, 2030003f}, {"미어캣", PAPER, 2030021f},
            {"기린", PAPER, 2030007f}, {"코끼리", PAPER, 2030002f},
            {"하마", PAPER, 2030005f}, {"플라밍고", PAPER, 2030004f},
            {"칠면조", PAPER, 2030006f}, {"발바닥", PAPER, 2030001f},
            // 재배 — 베이스 먼저, 그다음 작물
            {"토마토 베이스", PAPER, 2013014f},
            {"양파 베이스", PAPER, 2013021f},
            {"마늘 베이스", PAPER, 2013019f},
            {"토마토", PAPER, 2005038f},
            {"양파", PAPER, 2005024f},
            {"마늘", PAPER, 2005017f},
            {"옥수수", PAPER, 2005008f},
            {"파슬리", PAPER, 2005031f},
            {"파인애플", PAPER, 2013008f},
            {"코코넛", PAPER, 2013004f},
            {"감자", PAPER, 2013023f},
            {"호박", PAPER, 2013024f},
            {"당근", PAPER, 2013017f},
            {"비트", PAPER, 2013015f},
            {"수박", PAPER, 2013020f},
            {"열매", PAPER, 2013025f}, {"베리", PAPER, 2013025f},
            // 요리 — 완성 요리(cooked_beef). 유일 후보라 이름 근사 매칭(추정 표시는 매칭표 참고)
            {"스파게티", BEEF, 2004016f},
            {"어니언", BEEF, 2004014f},
            {"갈릭 케이크", BEEF, 2004005f},
            {"찌개", BEEF, 2004020f},
            {"아이스크림", BEEF, 2004012f},
            {"핫도그", BEEF, 2004010f},
            {"시리얼", BEEF, 2004023f},
            {"파이", BEEF, 2004024f},
            {"햄버거", BEEF, 2004009f},
            {"피자", BEEF, 2004011f},
            {"수프", BEEF, 2004006f}, {"스프", BEEF, 2004006f},
            {"찜", BEEF, 2004025f},
            {"라자냐", BEEF, 2004019f},
            {"빠네", BEEF, 2004027f},
            {"꼬치", BEEF, 2004026f},
            // 요리 식재료
            {"밀가루", PAPER, 2013001f}, // 밀가루반죽
            {"치즈", PAPER, 2013003f},
            {"소금", PAPER, 2013011f},
            {"설탕", PAPER, 2013013f},
            {"버터", PAPER, 2013002f},
            {"오일", PAPER, 2013005f},
            // 바리스타 — 커피 음료(enchanted_book), 팩 모델명과 1:1 확정
            {"아메리카노", BOOK, 2032001f},
            {"블랙 커피", BOOK, 2032003f},
            {"드립 커피", BOOK, 2032017f},
            {"콜드 브루", BOOK, 2032012f},
            {"카페 모카", BOOK, 2032005f},
            {"화이트 모카", BOOK, 2032042f},
            {"플랫 마끼아또", BOOK, 2032019f},
            {"머쉬룸 마끼아또", BOOK, 2032031f},
            {"코코아 마끼아또", BOOK, 2032009f},
            {"돌체 라떼", BOOK, 2032016f},
            {"그린티 라떼", BOOK, 2032026f},
            {"우드 라떼", BOOK, 2032043f},
            {"플라워 카푸치노", BOOK, 2032020f},
            {"썬더 카푸치노", BOOK, 2032041f},
            {"가든 카푸치노", BOOK, 2032022f},
            {"스톤 블렌디드", BOOK, 2032039f},
            {"쿠키 블렌디드", BOOK, 2032014f},
            {"다크 블렌디드", BOOK, 2032015f},
            {"그린트리 프라페", BOOK, 2032027f},
            {"체리 블로썸 프라페", BOOK, 2032007f},
            {"프로즌 스노우 프라페", BOOK, 2032021f},
            {"실버문 아인슈페너", BOOK, 2032036f},
            {"오로라 아인슈페너", BOOK, 2032002f},
            {"골든 아인슈페너", BOOK, 2032024f},
            {"원두", PAPER, 2032010f},
            // 배 낚시 부산물(repair_*) — 이름 대응 확정급
            {"심연의 오로라", PAPER, 2042003f},
            {"화석 연료", PAPER, 2042005f},
            {"고철", PAPER, 2042006f},
            {"티타늄", PAPER, 2042007f},
            {"열수구", PAPER, 2042004f},
            {"어선", PAPER, 2042101f},
            {"골드니", PAPER, 2012012f},
            // 낚시대회/파라다이스
            {"오로라 조각", PAPER, 2051005f},
            {"아쿠아 코인", PAPER, 2012049f},
            {"파라다이스 상자", PAPER, 2051004f},
            // 노크틸라
            {"정제된 루미디아", PAPER, 2035006f},
            {"루미디아의 조각", PAPER, 2035007f},
            {"루미디아", PAPER, 2035005f},
            {"에테르", PAPER, 2035056f},
            {"등급 룬", PAPER, 2034001f},
    };

    /** 거래 라벨 → 품목별 커스텀 아이콘. 없으면 null(→ 카테고리 대표). */
    public static Icon match(String label) {
        if (label == null || label.isEmpty()) return null;
        Icon i;
        if ((i = gradeRune(label)) != null) return i;
        if ((i = noctilaStone(label)) != null) return i;
        if ((i = alchemyTier(label)) != null) return i;
        if ((i = seafood(label)) != null) return i;
        if ((i = huntAnimal(label)) != null) return i;
        if ((i = dinoSoul(label)) != null) return i;
        if ((i = waveCrystal(label)) != null) return i;
        for (Object[] e : ENTRIES) {
            if (label.contains((String) e[0])) {
                return new Icon((String) e[1], (Float) e[2]);
            }
        }
        return null;
    }

    // ── 등급 룬: 무늬 무관, 등급 색으로만 구별(사용자 확정). rune1 타입 4등급 사용.
    //    루키=1급(구리 _1) 커먼=2급(은 _2) 노멀=3급(청록 _3) 레어=4급(파랑 _4).
    private static Icon gradeRune(String s) {
        if (!s.contains("등급 룬")) return null;
        int g;
        if (s.contains("레어")) g = 3;
        else if (s.contains("노멀")) g = 2;
        else if (s.contains("커먼")) g = 1;
        else if (s.contains("루키")) g = 0;
        else return null;
        return paper(2034001f + g); // rune1_1 ~ rune1_4
    }

    // ── 노크틸라 강화석 3종 × 등급(1~4급). 색: 수호=파랑(ac) 격파=빨강(wp) 각성=보라(sk) ──
    //    사용자 이미지로 전부 확정: 수호=파랑, 각성=보라. 격파=빨강(소거법).
    private static Icon noctilaStone(String s) {
        float base;
        if (s.contains("수호석")) base = 2035001f;       // ac_eh_stone(파랑)
        else if (s.contains("격파석")) base = 2035047f;   // wp_eh_stone(빨강)
        else if (s.contains("각성석")) base = 2035041f;   // sk_eh_stone(보라)
        else return null;
        int g = 0;
        for (int n = 4; n >= 1; n--) {
            if (s.contains(n + "급") || s.contains(n + "등급")) { g = n - 1; break; }
        }
        return paper(base + g);
    }

    // ── 어패류: 종류 × 성급(★/N성). 성급 없으면 3성 텍스처 ──
    private static final float[] OYSTER  = {2027069f, 2027078f, 2027079f};
    private static final float[] OCTOPUS = {2027067f, 2027075f, 2027074f};
    private static final float[] SEAWEED = {2027065f, 2027071f, 2027070f};
    private static final float[] URCHIN  = {2027066f, 2027073f, 2027072f};
    private static final float[] CONCH   = {2027068f, 2027077f, 2027076f};

    private static Icon seafood(String s) {
        int st = star(s);
        int idx = (st >= 1 ? st : 3) - 1;
        if (s.contains("굴"))   return paper(OYSTER[idx]);
        if (s.contains("문어")) return paper(OCTOPUS[idx]);
        if (s.contains("미역")) return paper(SEAWEED[idx]);
        if (s.contains("성게")) return paper(URCHIN[idx]);
        if (s.contains("소라")) return paper(CONCH[idx]);
        return null;
    }

    private static int star(String s) {
        if (s.contains("★★★") || s.contains("3성")) return 3;
        if (s.contains("★★")  || s.contains("2성")) return 2;
        if (s.contains("★")   || s.contains("1성")) return 1;
        return 0;
    }

    // ── 사냥 육식동물: 종류 × 등급(쇠약한_1/평범한_2/건강한_3, 기본 3) ──
    //    cmd 배열 = {_1, _2, _3}
    private static Icon huntAnimal(String s) {
        float[] c;
        if (s.contains("사자"))        c = new float[]{2030050f, 2030049f, 2030048f};
        else if (s.contains("표범"))   c = new float[]{2030044f, 2030043f, 2030042f};
        else if (s.contains("악어"))   c = new float[]{2030038f, 2030037f, 2030036f};
        else if (s.contains("늑대"))   c = new float[]{2030041f, 2030040f, 2030039f};
        else if (s.contains("호랑이")) c = new float[]{2030047f, 2030046f, 2030045f};
        else return null;
        int g = s.contains("쇠약") ? 0 : (s.contains("평범") ? 1 : 2);
        return paper(c[g]);
    }

    // ── 공룡 원혼: 종류(우티1~듀크5, 카탈로그 순서 추정) × 등급(일반1/희귀2/환상3) ──
    private static Icon dinoSoul(String s) {
        int t;
        if (s.contains("우티")) t = 1;
        else if (s.contains("라프")) t = 2;
        else if (s.contains("크록")) t = 3;
        else if (s.contains("헤탄")) t = 4;
        else if (s.contains("듀크")) t = 5;
        else return null;
        if (!s.contains("원혼")) return null; // 원혼만(다른 공룡 파생어 방지)
        int g = s.contains("환상") ? 3 : (s.contains("희귀") || s.contains("회귀") ? 2 : 1);
        return paper(2047031f + (t - 1) * 3 + (g - 1));
    }

    // ── 파도 결정석: 하급_1/중급_2/상급_3 (기본 하급) ──
    private static Icon waveCrystal(String s) {
        if (!s.contains("파도 결정석") && !(s.contains("파도") && s.contains("결정석"))) return null;
        if (s.contains("상급")) return paper(2051003f);
        if (s.contains("중급")) return paper(2051002f);
        return paper(2051001f);
    }

    // ── 연금 티어×속성: 종류(정수/에센스/엘릭서=t1 1~3성, 핵/결정/영약=t2 1~3성) × 속성 색 ──
    //    수호=blue 부식=green 파동=orange 혼란=purple 생명=red (엘릭서 5종 실측으로 확정된 규칙)
    //    [종류][색: blue,green,orange,purple,red]
    private static final float[][] T_CMD = {
            {2027054f, 2027050f, 2027053f, 2027052f, 2027051f}, // 정수(t1_1star)
            {2027049f, 2027045f, 2027048f, 2027047f, 2027046f}, // 에센스(t1_2star)
            {2027056f, 2027057f, 2027058f, 2027055f, 2027059f}, // 엘릭서(t1_3star) — 실측 확정
            {2027030f, 2027031f, 2027032f, 2027033f, 2027034f}, // 핵(t2_1star)
            {2027035f, 2027036f, 2027037f, 2027038f, 2027039f}, // 결정(t2_2star)
            {2027040f, 2027041f, 2027044f, 2027043f, 2027042f}, // 영약(t2_3star)
    };

    private static Icon alchemyTier(String s) {
        int color;
        if (s.contains("수호")) color = 0;
        else if (s.contains("부식")) color = 1;
        else if (s.contains("파동")) color = 2;
        else if (s.contains("혼란")) color = 3;
        else if (s.contains("생명") || s.contains("불멸") || s.contains("재생")) color = 4;
        else return null;
        int kind;
        if (s.contains("정수")) kind = 0;
        else if (s.contains("에센스")) kind = 1;
        else if (s.contains("엘릭서")) kind = 2;
        else if (s.contains("핵")) kind = 3;
        else if (s.contains("결정")) kind = 4;
        else if (s.contains("영약")) kind = 5;
        else return null;
        return paper(T_CMD[kind][color]);
    }

}
