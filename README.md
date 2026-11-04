# 품의 (Pumui)

구매요청(Purchase Request)을 Pull Request처럼 다루는 R&D 구매 시스템입니다.

> 핵심 원칙: **AI는 제안만 합니다. 체크, 리뷰어 지정, 발주 허가는 서버 코드가 맡습니다.**

**[프로젝트 사이트](https://happyherait.github.io/Pumui/)** · **[공개 데모](https://happyherait.github.io/Pumui/demo/)** · **[Q&A](docs/qna.md)**

회사, 사용자, 품목, 단가, 규제 등급은 모두 가상입니다. 구직용 프로토타입이며 특정 회사와 관련이 없습니다.

## 대응 관계

| GitHub | 품의 |
|---|---|
| Pull Request | 구매요청 |
| Commit | 사양 리비전 |
| Checks | 예산, 사양, 규제, 중복, 단가 체크 |
| CODEOWNERS | [PROCUREMENTOWNERS](shared/PROCUREMENTOWNERS) |
| Suggested change | AI 수정 제안 |
| Merge | 발주 전환 |

## 구조

```text
backend/rules/   의존성 없는 Java 규칙 엔진
backend/app/     Spring Boot API, Flyway, Oracle
frontend/        React + Vite, 서버 없는 데모 모드
shared/          골든 케이스, 리뷰어 규칙, 시드 데이터
docs/            ADR, Q&A
```

## 신뢰 경계

- **규제 판단:** 작성자나 AI가 고른 분류가 아니라 품목 마스터와 규제 물질 표로 판단합니다.
- **문서 격리:** 견적서 원문은 모델을 거치지 않고 저장하며, 서버가 CAS 번호를 직접 찾습니다.
- **승인 고정:** 승인은 리비전 해시에 묶입니다. 사양이 바뀌면 다시 받아야 합니다.
- **직무 분리:** 본인 승인, 한 사람의 복수 역할, 다른 팀 팀장의 승인을 거부합니다.
- **머지 재검증:** 행 잠금 안에서 최신 사실로 체크를 다시 실행합니다.
- **예산과 중복:** 조건부 UPDATE와 UNIQUE 제약으로 초과 지출과 중복 발주를 막습니다.
- **이력 보존:** 리비전, 리뷰, 이벤트는 Oracle 트리거로 수정과 삭제를 막습니다.

## 실행

Java 21, Maven, Node.js 22, Docker가 필요합니다. PowerShell 기준입니다.

```powershell
docker compose up -d --wait
mvn -f backend/pom.xml -DskipTests package
$env:ANTHROPIC_API_KEY = "키"   # .env는 자동으로 읽지 않습니다. 없으면 AI 기능만 503을 돌려줍니다
java -jar backend/app/target/pumui-app-0.1.0.jar
```

Oracle은 1522, API는 18080 포트를 씁니다. 다른 터미널에서 화면을 실행하고 http://localhost:5173 을 엽니다.

```powershell
cd frontend
npm install
npm run dev
```

서버 없이 화면만 보려면 `npm run dev:demo`를 실행합니다. VS Code에서는 `.vscode`의 실행 구성과 작업을 사용할 수 있습니다.

## 테스트

```powershell
mvn -f backend/pom.xml verify     # Oracle 실행 중이어야 합니다
cd frontend; npm test
```

## 한계

- 실제 Claude 호출은 공격 견적서 시나리오를 1회 수동 실행해 확인했습니다. 반복 평가는 아직 없고, 자동 테스트는 가짜 서버를 사용합니다.
- 사용자 식별은 데모용 헤더입니다. 운영에는 SSO가 필요합니다.
- CAS 번호 없이 품명만 있는 규제 물질은 자동으로 찾지 못합니다.

설계 결정은 [ADR](docs/adr)에 정리했습니다.
