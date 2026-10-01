package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogTest {

    @Test
    void 제보문은_디스코드_한도_안에서_최신_활동을_남긴다() {
        ActivityLog.clearForTest();
        for (int i = 0; i < 200; i++) ActivityLog.delta(-1000L * i);
        ActivityLog.unmatched("수상한 각인석 9개를 조사했습니다."); // 골드 없음 → 안 남김
        ActivityLog.unmatched("무언가 50,000골드를 받았습니다.");
        String r = ActivityLog.report(List.of("빌띵 0.2.8 · fabric"));
        assertTrue(r.length() <= 2_000, "len=" + r.length());
        assertTrue(r.contains("미인식 무언가 50,000골드"), "가장 최근 줄은 반드시 포함");
        assertFalse(r.contains("각인석"));
        assertTrue(r.startsWith("```\n빌띵 0.2.8"));
    }

    @Test
    void 활동이_없으면_없다고_쓴다() {
        ActivityLog.clearForTest();
        assertTrue(ActivityLog.report(List.of()).contains("(기록된 활동 없음)"));
    }
}
