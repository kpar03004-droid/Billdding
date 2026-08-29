package kr.ddingtycoon.dtledger.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.Identifier;

/**
 * 번들 GUI 텍스처(양피지 테마) 렌더 헬퍼 + 카테고리 아이콘 매핑.
 *
 * 텍스처는 assets/dtledger/textures/gui/sprites/*.png (+ 9-slice는 .png.mcmeta).
 * drawGuiTexture 가 스프라이트 아틀라스에서 찾아 .mcmeta 스케일링(nine_slice/stretch)으로 그림.
 * 색 팔레트는 Claude Design 스펙(양피지 라이트 테마).
 */
public final class GuiTex {
    private GuiTex() {}

    // ── 양피지(light) 팔레트 ──
    public static final int TEXT     = 0xFF4A3420;
    public static final int LABEL    = 0xFF7A5A34;
    public static final int TITLE    = 0xFF40301C;
    public static final int GOLD     = 0xFFA9791F;
    public static final int GOLD_BAR = 0xFFC8912A;
    public static final int GREEN    = 0xFF2F7D32;
    public static final int RED      = 0xFFB02E26;
    public static final int BLUE     = 0xFF2E5AA0;
    public static final int NEUTRAL  = 0xFF8A7A5A;
    public static final int BTN_TEXT = 0xFFF0E2C2; // 어두운 나무색 버튼(tex_btn_*) 위 밝은 크림 텍스트
    public static final int RULE     = 0x574A3420; // rgba(74,52,32,0.34)
    public static final int TRACK    = 0x294A3420; // rgba(74,52,32,0.16)

    public static Identifier id(String name) { return Identifier.of("dtledger", name); }

    /** 스프라이트를 (x,y)에 w×h로 그림. 9-slice/stretch 는 .mcmeta 가 결정. */
    public static void sprite(DrawContext ctx, String name, int x, int y, int w, int h) {
        ctx.drawGuiTexture(RenderLayer::getGuiTextured, id(name), x, y, w, h);
    }

    /** 가로 방향 원본 크기 타일 반복(9-slice 아닌 반복 텍스처, 예: 구분선). 우측을 scissor 로 자름. */
    public static void tileH(DrawContext ctx, String name, int left, int right, int y, int tileW, int tileH) {
        if (right <= left) return;
        ctx.enableScissor(left, y, right, y + tileH);
        for (int px = left; px < right; px += tileW) sprite(ctx, name, px, y, tileW, tileH);
        ctx.disableScissor();
    }

    /** 세로 방향 원본 크기 타일 반복(예: 스크롤바 트랙). 아래를 scissor 로 자름. */
    public static void tileV(DrawContext ctx, String name, int x, int top, int bottom, int tileW, int tileH) {
        if (bottom <= top) return;
        ctx.enableScissor(x, top, x + tileW, bottom);
        for (int py = top; py < bottom; py += tileH) sprite(ctx, name, x, py, tileW, tileH);
        ctx.disableScissor();
    }

    /** 카테고리 → 번들 아이콘 스프라이트 이름. */
    public static String icon(String category) {
        if (category == null) return "icon_coin";
        return switch (category) {
            case "사냥전문가" -> "icon_hunter";   // 건강한 표범
            case "채광전문가" -> "icon_mineral";  // 세렌트 주괴
            case "세공"       -> "icon_sego";
            case "공룡"       -> "icon_gongryong";
            case "바리스타"    -> "icon_barista";
            case "연금"       -> "icon_yeongeum";
            case "요리"       -> "icon_yori";
            case "해양전문가" -> "icon_fishing"; // 낚싯대
            case "재배전문가" -> "icon_jaebae";   // 토마토/작물
            case "공예품"     -> "icon_gongye";
            case "배 낚시"    -> "icon_baenaksi";
            case "낚시대회"    -> "icon_naksidaehoe";
            case "수족관"     -> "icon_suchokgwan"; // 반려어 방생 대금(물고기+금화)
            case "노크틸라"    -> "icon_noctila";
            case "무역"       -> "icon_muyeok";   // 나무 어선 획득권
            case "유저상점"    -> "icon_usershop";
            case "상점"       -> "icon_sangjeom";  // 마을 상점(NPC)
            case "판매"       -> "icon_panmae";
            case "바다의 가호" -> "icon_badaga";
            case "강화"       -> "icon_ganghwa";
            case "각인"       -> "icon_gagin";
            case "수리"       -> "icon_suri";      // 도구 내구도 수리·품질 회복
            case "전문가"     -> "icon_jeonmun";
            case "은행"       -> "icon_bank";
            case "수수료"     -> "icon_susuryo";
            case "송금"       -> "icon_songeum";
            case "수동"       -> "icon_sudong";    // 관리 탭에서 직접 입력한 기록
            case "의뢰" -> "icon_uiroe";
            case "인어의 축복" -> "icon_ineo";
            case "플로리스트"  -> "icon_florist";  // 세레니티 꽃 · 향장품
            case "마을 투자"   -> "icon_tuja";     // 회수 불가 지출(은행과 구분)
            default -> category.contains("플리마켓") ? "icon_flea" : "icon_coin";
        };
    }
}
