package kr.ddingtycoon.dtledger.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 모드 설정. 저장은 자체 Gson(config/dtledger/config.json) — YACL 는 화면 빌드에만 사용.
 * 따라서 YACL 이 없어도 설정 로드/저장은 정상 동작.
 */
public final class DtConfig {
    private static final Logger LOG = LoggerFactory.getLogger("dtledger");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ── 진단/소스 ──
    public boolean debugProbe = false;           // STEP1 진단 로거 — 잔고 소스 판정 완료(2026-07-20), 배포 기본 OFF
    public String balanceSourceMode = "AUTO";    // AUTO/SCOREBOARD/BOSSBAR/TABLIST/ACTIONBAR
    public String balanceMarker = "골드";         // 이 단어가 든 라인의 숫자를 우선 채택
    public String balanceRegex = "([0-9][0-9,]{2,})"; // 금액 토큰

    // ── 결합/회계 ──
    public long matchWindowMs = 1500;            // 메시지 ↔ ΔG 결합 시간창
    public boolean transferExcludedFromPnl = true; // 이체는 손익 제외
    public boolean feeCountedAsExpense = true;   // 수수료는 지출 포함
    public boolean showTransfers = true;         // 정산에 이체(참고) 표시
    public int dayResetHour = 0;                 // 하루 리셋 시각(0~23)

    /**
     * 금액을 못 알아낸 거래를 "미확인"으로 내역에 남길지. 끄면 예전처럼 조용히 버린다.
     * 켜두는 걸 권장 — 빠진 걸 유저가 모르고 지나가는 게 가장 나쁘다(2026-08-13 제보).
     */
    public boolean recordUnresolved = true;

    /**
     * 참고: 띵타이쿤 <b>일일 의뢰는 새벽 3시</b>에 초기화된다(2026-08-07 사용자 확인).
     * 장부 기준일과는 별개 개념이라 기본값은 건드리지 않는다 — 골드는 "들어온 시각"에 기록하는
     * 게 가계부로서 옳기 때문. 새벽 플레이를 한 날로 묶고 싶으면 dayResetHour 를 3 으로 두면
     * 서버 콘텐츠 주기와 맞는다.
     */
    public static final int QUEST_RESET_HOUR = 3;

    // ── 업데이트 알림 ──
    /** 새 버전 확인 여부. 끄면 네트워크 요청을 아예 안 한다. */
    public boolean updateCheckEnabled = true;
    /**
     * 버전 정보 JSON 주소. 비워두면 확인하지 않는다.
     * 형식: {"latest":"0.2.2","url":"다운로드 안내 주소","notes":"한 줄 요약"}
     *
     * <p>새 버전을 낼 때는 이 파일의 latest 만 고치면 된다(모드 재배포 불필요).
     * 공개 저장소라 로그인 없이 읽히며, 모드는 GET 한 번만 하고 아무것도 보내지 않는다.
     */
    public String updateCheckUrl =
            "https://raw.githubusercontent.com/kpar03004-droid/-/main/billding.json";

    // ── HUD ──
    public boolean hudEnabled = true;
    /**
     * 구버전 절대 픽셀 좌표. 이제는 hudXRatio/hudYRatio 가 진짜 값이고 이건 호환용으로만 남는다.
     * 기본값 726,373 은 1080p·GUI 크기 2(=960x540) 화면에서 잡힌 값이라, GUI 크기 3·4·'자동'
     * (=640x360, 480x270)에서는 처음부터 화면 밖이었다 — HUD 를 잡을 수 없다는 제보의 원인.
     */
    public int hudX = 726;
    public int hudY = 373;

    /**
     * HUD 위치를 화면 크기 대비 비율로 저장(0.0~1.0). GUI 크기·해상도가 바뀌어도
     * 화면상 같은 자리에 남는다. 음수면 "아직 승격 안 됨"(구버전 설정) → 화면 크기를 아는
     * 첫 순간에 hudX/hudY 를 그 화면 기준으로 환산해 채운다(ensureHudRatio).
     */
    public double hudXRatio = -1;
    public double hudYRatio = -1;

    /** 비율이 아직 없으면 현재 화면 기준으로 1회 승격. @return 승격이 일어났으면 true(저장 필요) */
    public boolean ensureHudRatio(int screenW, int screenH) {
        if (screenW <= 0 || screenH <= 0) return false;
        boolean changed = false;
        if (!isRatioSet(hudXRatio)) {
            hudXRatio = clampRatio((double) hudX / screenW);
            changed = true;
        }
        if (!isRatioSet(hudYRatio)) {
            hudYRatio = clampRatio((double) hudY / screenH);
            changed = true;
        }
        return changed;
    }

    /** 비율 → 픽셀. 승격 전이면 예전 픽셀값을 그대로 쓴다. */
    public int hudPixelX(int screenW) {
        return isRatioSet(hudXRatio) ? pixelFromRatio(hudXRatio, screenW) : hudX;
    }

    public int hudPixelY(int screenH) {
        return isRatioSet(hudYRatio) ? pixelFromRatio(hudYRatio, screenH) : hudY;
    }

    /** 편집 화면에서 위치가 바뀔 때 — 픽셀과 비율을 함께 갱신한다. */
    public void setHudPixel(int x, int y, int screenW, int screenH) {
        hudX = x;
        hudY = y;
        if (screenW > 0) hudXRatio = clampRatio((double) x / screenW);
        if (screenH > 0) hudYRatio = clampRatio((double) y / screenH);
    }

    private static boolean isRatioSet(double r) {
        return Double.isFinite(r) && r >= 0;
    }

    public static double clampRatio(double r) {
        return !Double.isFinite(r) ? 0 : Math.max(0, Math.min(1, r));
    }

    public static int pixelFromRatio(double ratio, int screenSize) {
        return screenSize <= 0 ? 0 : (int) Math.round(clampRatio(ratio) * screenSize);
    }

    /** HUD 크기 배율. 편집 화면에서 휠/버튼으로 조절(0.5~2.0). */
    public float hudScale = 1.0f;

    public static final float HUD_SCALE_MIN = 0.5f;
    public static final float HUD_SCALE_MAX = 2.0f;
    public static final float HUD_SCALE_STEP = 0.05f;

    /** 설정값이 손상돼도 안전한 범위로 보정한 배율. */
    public float hudScaleClamped() {
        if (!(hudScale > 0)) return 1.0f; // 0·음수·NaN 방어
        return Math.max(HUD_SCALE_MIN, Math.min(HUD_SCALE_MAX, hudScale));
    }
    public float hudOpacity = 1.0f; // 0.2~1.0 — HUD 패널 전체(배경+글자) 불투명도

    // ── 내 플리마켓 처리(전체계획서 §4-A) ──
    // AUTO: 메시지 확보 시 자동 집계 / EXCLUDE: 플리 손익 제외 / MANUAL: 수동 입력 UI
    public String fleaMarketMode = "AUTO";

    // ── 플리마켓 금고 (B안: 최초 UI 입력 + 자동 갱신, 언제든 재동기화) ──
    public long fleaVaultBalance = -1;   // -1 = 미설정
    public long vaultLimit = 20_000_000; // 금고 한도
    public boolean hudShowVault = true;  // HUD 에 금고 잔액 표시

    /**
     * 내 플리마켓 판매 수수료율(%) — 2026-07-27 제보: 판매 대금이 수수료 차감 전 금액으로 기록됨.
     * 실제로는 수수료가 선차감된 금액만 금고로 들어오므로, 순수령액(총액-수수료)만 수입으로 잡는다
     * (유저상점 판매와 동일한 처리). 0 이면 차감하지 않음. 서버 정책이 바뀌면 이 값만 조정.
     */
    public double fleaSaleFeePercent = 5.0;

    /**
     * "/플리마켓 금고" 재동기화 시, 추적값과 실측 잔액의 차액을 자동으로 수입/지출로 보정할지.
     * 잠수 중에는 다른 서버로 옮겨져 판매 채팅이 오지 않아 실시간 집계가 불가능하므로(2026-07-28),
     * 금고 잔액 차이를 "놓친 플리마켓 거래"로 기록해 메운다. 끄면 잔액만 맞추고 기록은 안 남김.
     */
    public boolean vaultSyncAutoRecord = true;

    // ※ 바다의 가호 감지는 창 제목이 아니라 창 안 아이템 설명(lore)으로 판별하도록 바뀌어
    //   (커스텀 리소스팩이라 제목을 신뢰할 수 없음) 별도 설정값이 필요 없다 — SeaBlessingTracker 참고.

    private transient Path path;

    /** @param dir 모드 설정 디렉터리(예: <configDir>/dtledger) — 각 로더 진입점이 주입 */
    public static DtConfig load(Path dir) {
        Path p = dir.resolve("config.json");
        DtConfig cfg;
        try {
            if (Files.exists(p)) {
                cfg = GSON.fromJson(Files.readString(p), DtConfig.class);
                if (cfg == null) cfg = new DtConfig();
            } else {
                cfg = new DtConfig();
            }
        } catch (Exception e) {
            LOG.warn("[dtledger] 설정 로드 실패, 기본값 사용", e);
            cfg = new DtConfig();
        }
        cfg.path = p;
        cfg.save();
        return cfg;
    }

    public void save() {
        if (path == null) return; // load() 전 호출 방지
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            LOG.warn("[dtledger] 설정 저장 실패", e);
        }
    }
}
