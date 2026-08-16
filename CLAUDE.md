# Dt Ledger — 띵타이쿤 골드 수입/지출 집계 모드

## 프로젝트
- Fabric **클라이언트 전용** QoL 모드. MC 1.21.4 / Java 21.
- 골드의 일일 수입·지출·이체를 자동 집계해 HUD/명령어로 표시.
- 패키지 루트: `kr.ddingtycoon.dtledger`.

## 절대 규칙 (컴플라이언스)
- 서버 화이트리스트제. **"이미 화면에 보이는 정보의 재구성·집계"만 허용** ("거울이지 망원경 아님").
- 금지: 자동화, 치트, 정보 우위(미표시 정보 수집·시세 탐색 등). **서버로 아무것도 전송 금지.**
- 모든 Watcher는 read-only. fabric.mod.json은 `environment: client`.

## 스택/의존성
- Fabric API, YACL(설정 화면 빌드에만), 번들 Gson(JSON 저장·자체 직렬화). Architectury·외부 DB 금지.
- 확정 버전: yarn 1.21.4+build.8 / loader 0.19.3 / fabric-api 0.119.4+1.21.4 / YACL 3.8.2+1.21.4-fabric.

## 회계 규칙 (핵심)
- 은행/플리마켓 **"금고"** 입출금 = TRANSFER(손익 제외). 수수료 = EXPENSE.
- **"플리마켓에서 구매" = EXPENSE(금고 아님!)**. '금고' 키워드로 이체/구매 구분.
- 판매/구매 메시지는 금액이 본문에 있으므로 그대로 사용, 잔고 ΔG로 교차검증.
- 타 플레이어 메시지(주어 있음)·내 잔고 무변동 = 무시.
- **이중계상 금지**: 플리 판매를 INCOME으로 세면 금고→잔고 출금은 절대 INCOME 금지(TRANSFER).

## 파싱 우선순위 (CurrencyParser)
- 코딩계획서 §6 표 순서 엄수(금고 이체 → 플리마켓 구매 → 유저상점 구매 → 판매 → catch-all).
- `CurrencyParser`는 `List<Rule>`로, **규칙 추가만으로 확장**(내일 확보분 3종 편입 지점).

## 🔴 미해결 이슈 (내일 메시지로 판정) — 전체계획서 §4-A
- 내 플리마켓 거래는 돈이 잔고 아닌 **금고**로 입출 → ΔG 신호 없음. 성사 채팅 메시지 유무가 관건.
- 메시지 있으면: FLEA_SALE=INCOME, FLEA_ORDER_FILLED=EXPENSE 규칙 활성화(현재 stub). 금고 잔액 추적 불필요.

## 진행 순서 (코딩계획서 STEP)
- STEP0 스캐폴딩 → **STEP1 진단로거로 잔고 소스 A/B 먼저 판정** → STEP2~8.
- STEP1이 A(텍스트)면 진행, B(픽셀)면 채팅 기반 집계로 전환 후 보고.
- 각 STEP 완료 기준을 `runClient`로 눈으로 확인 후 다음 STEP.

## 빌드
- `gradle wrapper --gradle-version 8.10` 로 wrapper jar 생성 후 `./gradlew runClient` (또는 IntelliJ에서 Import).
- ⚠️ 실제 빌드는 OneDrive·한글 경로 밖(예: `C:\dev\dtledger`)을 권장 — Loom 디컴파일/파일락 이슈 회피.
