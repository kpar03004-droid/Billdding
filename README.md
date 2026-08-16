# Dt Ledger

띵타이쿤(ddingtycoon.kr) 서버용 **골드 수입/지출 자동 집계** Fabric 클라이언트 전용 QoL 모드.
화면에 이미 보이는 정보(좌상단 골드 잔고 · 채팅 로그)만 재구성·집계합니다. 자동화·정보우위 기능 없음.

- MC **1.21.4** / Fabric / Java **21** / 클라이언트 전용
- 판매(수입) · 구매(지출) · 은행/플리마켓 금고 이체 · 수수료를 자동 분류·집계
- `/dtstat` 명령어 + HUD 오버레이로 오늘/주간 정산 표시

## 빌드 / 실행

Gradle Wrapper가 포함돼 있어 별도 Gradle 설치 없이 바로 실행됩니다(최초 실행 시 Gradle 8.10 자동 다운로드):

```bash
./gradlew runClient    # 개발용 클라 실행 (Windows: gradlew.bat runClient)
./gradlew build        # 배포 jar 생성 (build/libs)
./gradlew test         # 파서 오프라인 검증(JUnit)
```

또는 IntelliJ IDEA에서 `build.gradle`을 Import (권장).

> ⚠️ **경로 주의**: OneDrive 동기화 폴더나 한글 경로에서 Loom 빌드 시 파일락/디컴파일 문제가 날 수 있습니다.
> 실제 빌드는 `C:\dev\dtledger` 같은 ASCII 경로로 복사해서 진행하는 것을 권장합니다.

## 구현 진행 (코딩계획서 STEP)

| STEP | 산출물 | 상태 |
|---|---|---|
| 0 | 스캐폴딩 | ✅ |
| 1 | BalanceProbe (잔고 소스 A/B 진단) | ✅ (게임에서 판정 필요) |
| 2 | BalanceWatcher (ΔG 감지) | ✅ |
| 3 | ChatWatcher + CurrencyParser | ✅ |
| 4 | TransactionResolver + TransferClassifier | ✅ |
| 5 | LedgerStore (JSON 저장) | ✅ |
| 6 | DailyAggregator + /dtstat | ✅ |
| 7 | LedgerHud | ✅ |
| 8 | DtConfig (YACL) | ✅ |

## 명령어

- `/dtstat` 또는 `/dtstat ui` — 정산 창(GUI: 오늘/주간/미분류/금고 탭)
- `/dtstat vault [금액]` — 플리마켓 금고 잔액 확인/설정(재동기화). 최초 1회 입력 후 자동 갱신
- `/dtstat today` — 오늘 정산 (채팅 텍스트)
- `/dtstat week` — 최근 7일
- `/dtstat day <YYYY-MM-DD>` — 특정 날짜
- `/dtstat pending` — 미분류(기타) 내역
- `/dtstat export` — CSV 내보내기
- `/dtstat config` — 설정 화면 열기
- `/dtstat hud` — HUD 표시 전환

## 컴플라이언스

"거울이지 망원경이 아니다." 이미 보이는 정보의 재구성/집계만 수행하며, 서버로 어떤 데이터도 전송하지 않습니다. MIT 오픈소스.

## 라이선스

MIT
