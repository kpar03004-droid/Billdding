# 구현 현황 (2026-07-20)

STEP 0~8 전 코드 작성 완료. 아래는 상태와 남은 검증/작업.

## ✅ 완료 (코드)

| STEP | 파일 | 상태 |
|---|---|---|
| 0 스캐폴딩 | build.gradle, settings.gradle, gradle.properties, wrapper, fabric.mod.json, lang | ✅ |
| 1 진단로거 | `debug/BalanceProbe.java` | ✅ (게임에서 A/B 판정 필요) |
| 2 잔고감지 | `watcher/BalanceWatcher` + `BalanceSource`(Scoreboard/BossBar/TabList) | ✅ |
| 3 채팅파싱 | `watcher/ChatWatcher`, `core/CurrencyParser`(List<Rule>), `core/TradeSignal` | ✅ **테스트 10/10 통과** |
| 4 결합/분류 | `core/TransactionResolver`, `core/TransferClassifier`, `core/TransactionRecord` | ✅ |
| 5 저장 | `store/LedgerStore`(월별 JSON, 5초 디바운스) | ✅ |
| 6 집계/명령 | `aggregate/DailyAggregator`, `DailyBucket`, `ui/DtStatCommand` | ✅ |
| 7 HUD | `ui/LedgerHud` — 카드형 패널(액센트 바·우측정렬·이체 행) | ✅ |
| 7+ 정산창 | `ui/DtStatScreen` — 오늘/주간/미분류/금고 4탭 GUI, 카테고리 막대·주간 다이버징 차트 (`/dtstat ui`·키바인드) | ✅ |
| 7+ 금고추적 | `core/VaultTracker` — B안: 최초 UI/명령 입력 + 플리 판매·구매·입출금 자동 갱신, 한도 2,000만 클램프·경고, 재동기화. **테스트 14/14 통과** | ✅ |
| 8 설정 | `config/DtConfig`(Gson), `config/DtConfigScreen`(YACL) | ✅ |
| 부가 | `mixin/*Accessor`(보스바/탭리스트 읽기), `util/*`, JUnit 테스트 | ✅ |

파서 확정 정규식은 오프라인에서 실측 원문 10건으로 검증 완료(`./gradlew test`).

## ⚠️ 런타임 검증 필요 (runClient에서 확인 — MC 없이는 컴파일 검증 불가)

아래는 1.21.4 Yarn 매핑 기준으로 작성했으나 **실제 빌드에서 메서드/필드명이 다르면 여기서만 수정**하면 됩니다(설계 불변):

1. **스코어보드 API** — `ScoreboardBalanceSource`: `getScoreboardEntries`, `getScoreHolderTeam`, `ScoreboardEntry.owner()/value()`. try/catch로 감쌌으나 컴파일명은 맞아야 함.
2. **Mixin accessor 필드명** — `PlayerListHudAccessor`(header/footer), `BossBarHudAccessor`(bossBars).
3. **HUD 콜백** — `HudRenderCallback`(2번째 인자 Object로 받아 버전차 회피). 1.21.4에서 deprecated면 `HudLayerRegistrationCallback`로 교체.
4. **YACL 화면 API** — `DtConfigScreen`. 런타임 try/catch로 실패 시 null 반환(핵심기능 무영향). API 상이 시 이 파일만 수정.
5. **inGameHud.getBossBarHud()/getPlayerListHud()** 존재 확인.
6. **DtStatScreen** — `Screen.renderBackground(ctx,mx,my,delta)` 시그니처, `ButtonWidget.builder(...).dimensions(...)`, `MinecraftClient.send(Runnable)`. 이름이 다르면 이 파일만 수정.

> 이 5개는 전부 코딩계획서가 "STEP1 runClient로 확인"이라고 명시한 지점입니다.

## 🔜 다음 액션 (순서대로)

1. **빌드**: ASCII 경로(예: `C:\dev\dtledger`)로 복사 후 `gradlew runClient`. (OneDrive/한글 경로 파일락 회피 — README 참고)
2. **STEP 1 판정**: 게임 접속 → 콘솔의 `[dtledger-probe]` 로그에서 골드값(예: 6,240,574)이 **어느 소스**(scoreboard/bossbar/tablist/actionbar)에 있는지 확인.
   - 있으면 → `config/dtledger/config.json`의 `balanceSourceMode`를 해당 소스로, `debugProbe`를 `false`로.
   - 없으면(픽셀) → 채팅 입금/판매 기반 집계로 전환 검토 후 보고.
3. **원문 확보 현황** (전체계획서 §4-A):
   - ✅ 해결(2026-07-20 실측 편입): 내 플리 판매("구매되었습니다")·매수 체결("판매받았습니다")·직거래 송금("님에게 보냈/받았습니다")·거래창 요약("받은/보낸 골드"). 거래창↔송금 이중보고는 Resolver 중복제거로 방어.
   - ⏳ 남은 확인: NPC별 판매 문구(샤키/픽스/수렵꾼)가 "판매하셨습니다"와 다른지, 은행 출금 실문구(현재 추정규칙 활성).
   - 라이브에서 확인할 것: 같은 거래에서 거래창 요약과 송금 메시지가 **둘 다 뜨는지**(중복제거 로그 `중복 송금 신호 무시`로 확인 가능).
4. `./gradlew test`로 파서 회귀 확인 → `/dtstat today`·HUD 육안 검증.

## 컴플라이언스 확인

- `environment: client`, 모든 Watcher read-only, 서버 송신 코드 0. `/dtstat` 출력은 로컬 채팅 피드백.
