package kr.ddingtycoon.dtledger.ui;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * "§6§l 빌띵§r …" 같은 옛 색 코드 문자열을 스타일 텍스트로 바꿔 채팅에 보낸다.
 *
 * <p>{@code Text.literal("§6…")}은 환경에 따라 색이 안 먹고 "6l 빌띵rf"처럼 코드 글자가 그대로
 * 찍힌다(2026-10-02 업데이트 알림 제보). 문자열 안의 § 를 직접 풀어 Style 로 넘기면 어디서든 같다.
 * 기존 메시지 문구(§ 포함)는 그대로 두고 보내는 길목만 이걸 거친다.
 */
public final class ChatText {
    private ChatText() {}

    public static MutableText of(String s) {
        MutableText out = Text.empty();
        if (s == null) return out;
        Style style = Style.EMPTY;
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                Formatting f = Formatting.byCode(s.charAt(++i));
                if (f == null) continue;
                if (seg.length() > 0) {
                    out.append(Text.literal(seg.toString()).setStyle(style));
                    seg.setLength(0);
                }
                // 색 코드는 앞의 굵게·밑줄을 지운다(바닐라 § 규칙과 같음), §r 은 전부 초기화
                style = f == Formatting.RESET ? Style.EMPTY
                        : f.isColor() ? Style.EMPTY.withFormatting(f) : style.withFormatting(f);
                continue;
            }
            seg.append(c);
        }
        if (seg.length() > 0) out.append(Text.literal(seg.toString()).setStyle(style));
        return out;
    }
}
