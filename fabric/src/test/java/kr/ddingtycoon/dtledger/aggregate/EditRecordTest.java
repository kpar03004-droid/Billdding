package kr.ddingtycoon.dtledger.aggregate;

import kr.ddingtycoon.dtledger.config.DtConfig;
import kr.ddingtycoon.dtledger.core.TransactionRecord;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Confidence;
import kr.ddingtycoon.dtledger.core.TransactionRecord.Kind;
import kr.ddingtycoon.dtledger.store.LedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class EditRecordTest {

    @Test
    void 내역에서_고친_금액과_카테고리가_집계와_파일에_반영된다(@TempDir Path dir) {
        DtConfig config = new DtConfig();
        LedgerStore store = new LedgerStore(dir);
        DailyAggregator agg = new DailyAggregator(config, store);
        agg.today(); // 실제 시작 순서: 원장 먼저 로드 → 이후 라이브 레코드
        TransactionRecord r = new TransactionRecord(System.currentTimeMillis(), Kind.EXPENSE, 10_000_000,
                "강화", "강화 성공", 0, true, Confidence.MEDIUM, false, null);
        store.commit(r);
        agg.addLive(r);
        assertEquals(10_000_000, agg.today().expense);

        agg.editRecord(r, 5_000_000, "강화", "강화 성공 4강");

        DailyBucket b = agg.today();
        assertEquals(5_000_000, b.expense);
        assertEquals(-5_000_000, b.wallet);
        assertEquals(1, b.count, "고쳐도 건수는 그대로");
        assertTrue(r.note.contains("원래 10,000,000"), r.note);
        assertEquals(Confidence.HIGH, r.confidence);

        // 파일에서 다시 읽어도 고친 값
        TransactionRecord reloaded = new LedgerStore(dir).loadMonth(YearMonth.now()).get(0);
        assertEquals(5_000_000, reloaded.amount);
        assertEquals("강화 성공 4강", reloaded.label);
    }
}
