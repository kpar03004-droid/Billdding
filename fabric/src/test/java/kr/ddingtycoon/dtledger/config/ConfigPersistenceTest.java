package kr.ddingtycoon.dtledger.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 설정 저장·로드의 안전장치 검증.
 *
 * <p>[3] 원자적 쓰기 — 저장 중 임시 파일을 거쳐 교체되므로 도중 크래시해도 직전 설정이 남는다.
 * <p>[5] 범위 보정 — 손으로 편집한 config.json 의 잘못된 수치를 유효 범위로 되돌린다.
 */
class ConfigPersistenceTest {

    @Test
    void 손상된_수치는_로드시_유효범위로_보정된다(@TempDir Path dir) throws Exception {
        // 유저가 config.json 을 직접 잘못 편집한 상황
        Files.writeString(dir.resolve("config.json"), """
            {
              "dayResetHour": 99,
              "hudOpacity": 5.0,
              "fleaSaleFeePercent": -3.0,
              "matchWindowMs": 1,
              "vaultLimit": 0
            }
            """);

        DtConfig cfg = DtConfig.load(dir);

        assertEquals(23, cfg.dayResetHour, "0~23 로 clamp");
        assertEquals(1.0f, cfg.hudOpacity, "0.2~1.0 로 clamp");
        assertEquals(0.0, cfg.fleaSaleFeePercent, "0 이상으로 clamp");
        assertEquals(200, cfg.matchWindowMs, "하한 200ms");
        assertTrue(cfg.vaultLimit > 0, "0 한도는 % 계산을 깨뜨리므로 기본값 복구");
    }

    @Test
    void 정상값은_건드리지_않는다(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config.json"), """
            {
              "dayResetHour": 3,
              "hudOpacity": 0.7,
              "fleaSaleFeePercent": 5.0,
              "matchWindowMs": 1500
            }
            """);

        DtConfig cfg = DtConfig.load(dir);

        assertEquals(3, cfg.dayResetHour);
        assertEquals(0.7f, cfg.hudOpacity, 1e-6);
        assertEquals(5.0, cfg.fleaSaleFeePercent, 1e-9);
        assertEquals(1500, cfg.matchWindowMs);
    }

    @Test
    void NaN_이_들어와도_기본값으로_되돌린다(@TempDir Path dir) throws Exception {
        // Gson 은 JSON 의 NaN 을 그대로 역직렬화한다 — 유한값이 아니면 기본값으로.
        Files.writeString(dir.resolve("config.json"), """
            { "hudOpacity": NaN, "fleaSaleFeePercent": Infinity }
            """);

        DtConfig cfg = DtConfig.load(dir);

        assertEquals(1.0f, cfg.hudOpacity, "NaN → 기본값");
        assertEquals(5.0, cfg.fleaSaleFeePercent, "Infinity → 기본값");
    }

    @Test
    void 저장은_임시파일을_거쳐_교체한다(@TempDir Path dir) throws Exception {
        DtConfig cfg = DtConfig.load(dir);   // load 가 곧바로 save 도 부른다
        cfg.hudX = 123;
        cfg.save();

        Path json = dir.resolve("config.json");
        assertTrue(Files.exists(json), "설정 파일이 있어야 한다");
        assertFalse(Files.exists(dir.resolve("config.json.tmp")), "임시 파일은 교체 후 남지 않는다");

        // 다시 읽어 값이 살아 있는지
        DtConfig again = DtConfig.load(dir);
        assertEquals(123, again.hudX);
    }
}
