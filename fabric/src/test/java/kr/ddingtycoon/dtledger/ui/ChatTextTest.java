package kr.ddingtycoon.dtledger.ui;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTextTest {

    @Test
    void 색_코드는_글자로_남지_않고_스타일이_된다() {
        Text t = ChatText.of("§6§l 빌띵§r§f  새 버전 §a§l0.2.8§r §7(현재 0.2.7)");
        assertEquals(" 빌띵  새 버전 0.2.8 (현재 0.2.7)", t.getString(), "§ 와 코드 글자가 사라져야 함");
        Text first = t.getSiblings().get(0);
        assertEquals(" 빌띵", first.getString());
        assertEquals(Formatting.GOLD.getColorValue(), first.getStyle().getColor().getRgb());
        assertTrue(first.getStyle().isBold());
        Text version = t.getSiblings().get(2);
        assertEquals("0.2.8", version.getString());
        assertTrue(version.getStyle().isBold());
    }

    @Test
    void 코드_없는_문장은_그대로() {
        assertEquals("오늘 순익 +1,000", ChatText.of("오늘 순익 +1,000").getString());
        assertEquals("", ChatText.of(null).getString());
    }
}
