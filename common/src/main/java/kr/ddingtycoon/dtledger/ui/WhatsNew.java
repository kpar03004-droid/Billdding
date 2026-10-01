package kr.ddingtycoon.dtledger.ui;

import java.util.List;

/**
 * 업데이트 후 처음 창을 열면 오늘 탭 맨 위에 한 번 뜨는 「새로워진 점」.
 * 새 버전을 낼 때 {@link #VERSION}과 {@link #LINES}만 바꾼다. 줄은 3개 이하, 짧게.
 */
public final class WhatsNew {
    private WhatsNew() {}

    public static final String VERSION = "0.2.8";
    public static final List<String> LINES = List.of(
            "잔고 대조: 기록에 없는 잔고 변동을 오늘 탭 아래에 알려줘요",
            "내역 줄 클릭 = 금액·카테고리 수정 · /빌띵 제보 = 제보용 복사",
            "Ctrl+C 로 오늘·주간 정산 복사 · 숫자키 1~6 탭 이동");

    /** 이 사용자가 아직 이번 버전 소식을 안 봤는가. */
    public static boolean unseen(String lastSeen) {
        return !VERSION.equals(lastSeen);
    }
}
