# JVM 로깅 성능 5단계 실험 계획서

## 1. 실험 배경

인프라팀에서 운영 환경의 CloudWatch Logs 비용이 비정상적으로 높다는 사실을 공유했다.
`jdbc.resultset=INFO` 설정으로 인해 1시간에 2.7억 건(34GB)의 로그가 발생하고 있었고,
CloudWatch는 수집된 로그 GB당 과금되므로 불필요한 로그가 비용에 직결된다.

인프라팀은 각 도메인 개발자들에게 불필요한 INFO 로그를 줄이는 것을 제안했고,
이를 계기로 로그가 성능에 미치는 영향을 학습 목적으로 실험해 보기로 했다.

## 2. 실험 목적

로깅 설정이 애플리케이션 성능에 미치는 영향을
부하 테스트(k6)와 JVM 프로파일링(VisualVM)으로 정량적으로 측정한다.

측정 항목:
- 로깅 설정 변경에 따른 TPS(초당 처리량) 변화
- CPU 사용률, 힙 메모리 사용량, GC 빈도 변화
- 동기/비동기 Appender 간 스레드 블로킹 차이

## 3. 실험 환경

### 기술 스택

| 구성 요소 | 선택 | 비고 |
|-----------|------|------|
| 프레임워크 | Spring Boot 4.0.3 | |
| 데이터베이스 | H2 (In-memory) | DB I/O 변수 제거 |
| 빌드 도구 | Gradle (Kotlin DSL) | |
| Java | 21 | |
| 부하 테스트 | k6 | 100 VUser, 1분 |
| 프로파일링 | VisualVM | CPU, Heap, GC 추적 |

### 테스트 시나리오

- 더미 데이터 500건을 H2에 INSERT (애플리케이션 시작 시 자동)
- `GET /api/logs/test` → `findAll()`로 500건 전체 조회 후 반환
- k6로 100 VUser × 1분 지속 호출

### 통제 변인

- H2 In-memory를 사용하여 네트워크/디스크 I/O 변수를 제거하고,
  로깅이 애플리케이션 스레드에 미치는 영향만 측정한다.
- 500건 조회는 jdbc.resultset 로그가 켜졌을 때
  한 요청당 약 2,500줄의 로그를 유발하기에 충분한 양이다.

---

## 4. 실험 설계 (5단계)

### 독립 변인 매트릭스

| Phase | Appender 방식 | 로그 포맷 | jdbc.resultset |
|-------|--------------|-----------|----------------|
| 1 | - (로깅 OFF) | - | OFF |
| 2 | 동기(Sync) | Text (PatternLayout) | ON |
| 3 | 동기(Sync) | JSON (JsonTemplateLayout) | ON |
| 4 | 비동기(Async) | JSON (JsonTemplateLayout) | ON |
| 5 | 비동기(Async) | JSON (JsonTemplateLayout) | OFF |

### 종속 변인 (측정 지표)

- TPS (k6 `http_reqs`)
- 응답 시간 (k6 `http_req_duration` p95/p99)
- CPU 사용률 (VisualVM)
- 힙 메모리 사용량 및 GC 빈도 (VisualVM)
- 스레드 상태 분포 - Blocked/Waiting 비율 (VisualVM)

---

### Phase 1: 대조군 (Baseline)

| 항목 | 설정 |
|------|------|
| 로깅 레벨 | Root=WARN |
| jdbc 로깅 | OFF |
| 로그 포맷 | - |
| Appender | - |

목적: 로깅이 없는 상태의 순수 API 조회 TPS 기준점 확립.

---

### Phase 2: 동기 + 텍스트 + jdbc ON

| 항목 | 설정 |
|------|------|
| 로깅 레벨 | Root=INFO |
| jdbc 로깅 | jdbc.resultset=DEBUG |
| 로그 포맷 | 텍스트 (PatternLayout) |
| Appender | 동기(Sync) Console (SYSTEM_OUT) |

가설: 콘솔 텍스트 출력의 I/O가 Tomcat 워커 스레드를 블로킹하여 TPS가 Phase 1 대비 감소한다.

---

### Phase 3: 동기 + JSON + jdbc ON

| 항목 | 설정 |
|------|------|
| 로깅 레벨 | Root=INFO |
| jdbc 로깅 | jdbc.resultset=DEBUG |
| 로그 포맷 | JSON (JsonTemplateLayout) |
| Appender | 동기(Sync) Console (SYSTEM_OUT) |

가설: Phase 2의 I/O 블로킹에 JSON 직렬화 비용이 추가되어 TPS가 Phase 2보다 추가 감소한다.

---

### Phase 4: 비동기 + JSON + jdbc ON

| 항목 | 설정 |
|------|------|
| 로깅 레벨 | Root=INFO |
| jdbc 로깅 | jdbc.resultset=DEBUG |
| 로그 포맷 | JSON (JsonTemplateLayout) |
| Appender | 비동기(Async) - Log4j2 AsyncLogger + Disruptor |

가설: Disruptor 큐가 Tomcat 워커 스레드의 블로킹을 해소하여 TPS가 Phase 3 대비 회복된다.

---

### Phase 5: 비동기 + JSON + jdbc OFF

| 항목 | 설정 |
|------|------|
| 로깅 레벨 | Root=INFO |
| jdbc 로깅 | OFF |
| 로그 포맷 | JSON (JsonTemplateLayout) |
| Appender | 비동기(Async) - Log4j2 AsyncLogger + Disruptor |

가설: jdbc 로그를 OFF 하면 로그 I/O 부하가 사라져 Phase 1에 근접한 TPS로 회복된다.

---

## 5. 측정 및 수집 항목

### k6 결과 (Phase별 수집)

| Phase | 설정 요약 | TPS | p95 (ms) | p99 (ms) |
|-------|----------|-----|----------|----------|
| 1 | Baseline | - | - | - |
| 2 | Sync + Text + jdbc ON | - | - | - |
| 3 | Sync + JSON + jdbc ON | - | - | - |
| 4 | Async + JSON + jdbc ON | - | - | - |
| 5 | Async + JSON + jdbc OFF | - | - | - |

### JVM 프로파일링 (Phase별 캡처)

- CPU 사용률 그래프
- 힙 메모리 + GC 활동 그래프
- 스레드 상태 분포 (Blocked/Waiting 비율)

---

## 6. 프로젝트 구조

```
logging-lab/
├── docs/
│   └── experiment-plan.md           # 본 문서
├── src/main/java/com/example/logginglab/
│   ├── LoggingLabApplication.java
│   ├── controller/
│   │   └── LogTestController.java   # GET /api/logs/test
│   ├── entity/
│   │   └── DummyEntity.java         # H2 더미 엔티티
│   ├── repository/
│   │   └── DummyRepository.java     # JpaRepository
│   └── service/
│       └── DummyService.java        # findAll()
├── src/main/resources/
│   ├── application.properties
│   ├── log4j2-phase{1~5}.xml
│   └── log4jdbc.log4j2.properties
├── k6/
│   └── load-test.js                 # k6 부하 테스트 스크립트
├── build.gradle.kts
└── settings.gradle.kts
```

## 7. 실행 방법

```bash
# Phase 1: Baseline
./gradlew bootRun -Dspring.profiles.active=phase1

# Phase 2: Sync + Text + jdbc ON
./gradlew bootRun -Dspring.profiles.active=phase2

# Phase 3: Sync + JSON + jdbc ON
./gradlew bootRun -Dspring.profiles.active=phase3

# Phase 4: Async + JSON + jdbc ON
./gradlew bootRun -Dspring.profiles.active=phase4

# Phase 5: Async + JSON + jdbc OFF
./gradlew bootRun -Dspring.profiles.active=phase5

# k6 부하 테스트 (별도 터미널)
k6 run k6/load-test.js
```
