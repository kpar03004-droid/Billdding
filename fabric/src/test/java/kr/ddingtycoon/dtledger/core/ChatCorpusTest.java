package kr.ddingtycoon.dtledger.core;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** chat-corpus.tsv 의 실제 채팅을 전부 파싱해 기대값과 비교 — 틀린 줄을 한꺼번에 보여준다. */
class ChatCorpusTest {

    @Test
    void 실제_채팅_모음이_모두_기대대로_읽힌다() throws Exception {
        CurrencyParser parser = CurrencyParser.createDefault();
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        try (var in = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/chat-corpus.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] f = line.split("\t");
                String raw = f[0], type = f[1];
                long amount = Long.parseLong(f[2]);
                String cat = f.length > 3 ? f[3] : "";
                checked++;

                TradeSignal s = parser.parse(raw);
                String got = s == null ? "NONE" : s.type.name();
                if (!got.equals(type)) {
                    wrong.add(raw + " → 유형 " + got + " (기대 " + type + ")");
                    continue;
                }
                if (s == null) continue;
                if (s.amount != amount) wrong.add(raw + " → 금액 " + s.amount + " (기대 " + amount + ")");
                if (!cat.isEmpty()) {
                    String c = SaleCategory.of(s.label, s.raw);
                    if (!cat.equals(c)) wrong.add(raw + " → 카테고리 " + c + " (기대 " + cat + ")");
                }
            }
        }
        assertTrue(checked > 0, "모음 파일을 못 읽음");
        assertTrue(wrong.isEmpty(), "틀린 줄 " + wrong.size() + "개:\n" + String.join("\n", wrong));
    }
}
