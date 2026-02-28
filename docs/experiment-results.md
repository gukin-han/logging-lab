# 실험 결과

- 실험 일시: 2026-02-20
- 부하 조건: 100 VUser, 1분
- API: `GET /api/logs/test` (500건 조회)

---

## 1차 실험: jdbc.resultset (요청당 ~2,500줄)

### TPS 비교표

| Phase | 설정 요약 | TPS | avg (ms) | p95 (ms) | Baseline 대비 |
|-------|----------|-----|----------|----------|---------------|
| 1 | Baseline (로깅 OFF) | **2,485** | 40.18 | 173.37 | 100% |
| 2 | Sync + Text + jdbc.resultset ON | **14.6** | 6,510 | 7,180 | 0.59% |
| 3 | Sync + JSON + jdbc.resultset ON | **13.5** | 7,000 | 7,600 | 0.54% |
| 4 | Async + JSON + jdbc.resultset ON | **18.3** | 5,240 | 5,720 | 0.74% |
| 5 | Async + JSON + jdbc.resultset OFF | **2,429** | 41.12 | 176.62 | 97.7% |

### 관찰

- Phase 1→2: TPS 2,485 → 14.6으로 약 170배 감소. jdbc.resultset이 요청당 2,500+줄의 로그를 생성하면서 스레드가 I/O에 블로킹됨.
- Phase 2→3: TPS 14.6 → 13.5로 차이 미미 (7.5%). I/O 블로킹이 지배적인 상황에서 JSON 직렬화 비용은 측정되지 않음.
- Phase 3→4: TPS 13.5 → 18.3으로 약 35% 증가. 그러나 Baseline 대비 여전히 0.74%로, 이 로그량 수준에서는 Async 전환만으로는 한계가 있음.
- Phase 4→5: TPS 18.3 → 2,429로 Baseline의 97.7% 수준까지 회복. jdbc.resultset OFF로 로그 I/O 부하가 사라짐.

### 한계

Phase 2~4가 모두 TPS 13~18 범위에 몰려있어, Sync/Async 차이와 Text/JSON 차이가 유의미하게 드러나지 않았다. 로그량이 극단적이어서 모든 설정이 유사한 수준으로 수렴한 것으로 보인다.

---

## 2차 실험: jdbc.sqltiming (요청당 1~2줄)

jdbc.resultset 대신 **jdbc.sqltiming**(요청당 1~2줄)으로 로그량을 줄여, Phase 간 차이가 드러나는지 확인.

### TPS 비교표

| Phase | 설정 요약 | TPS | avg (ms) | p95 (ms) | Baseline 대비 |
|-------|----------|-----|----------|----------|---------------|
| 1 | Baseline (로깅 OFF) | **2,485** | 40.18 | 173.37 | 100% |
| 2b | Sync + Text + jdbc.sqltiming ON | **1,955** | 51.08 | 209.81 | 78.7% |
| 3b | Sync + JSON + jdbc.sqltiming ON | **1,982** | 50.39 | 204.87 | 79.8% |
| 4b | Async + JSON + jdbc.sqltiming ON | **2,004** | 49.83 | 202.52 | 80.6% |

### 관찰

- Phase 1→2b: TPS 2,485 → 1,955로 약 21% 감소. 로그량이 적은 경우 I/O 부담은 있지만 전체 처리량에 대한 영향은 제한적.
- Phase 2b→3b: TPS 1,955 → 1,982로 차이 없음. 이 로그량에서는 JSON 직렬화 비용이 측정되지 않음.
- Phase 3b→4b: TPS 1,982 → 2,004로 차이 없음. 이 로그량에서는 Sync/Async 차이도 측정되지 않음.

### 해석

jdbc.sqltiming 수준의 로그량에서는 Sync/Async, Text/JSON 간 차이가 오차 범위 수준이었다. 이 실험 조건에서는 세 설정 모두 유사한 수준으로 수렴했다.

---

## VisualVM 프로파일링 결과

Phase 2(Sync + jdbc.resultset ON), Phase 4(Async + jdbc.resultset ON), Phase 5(Async + jdbc.resultset OFF)를 VisualVM으로 모니터링했다.

### 목적

k6 성능 테스트 수치만으로는 "왜 느린지"를 알 수 없었다. CPU, 힙 메모리, GC 활동을 확인하여 병목 지점의 단서를 얻기 위해 VisualVM을 추가했다.

### 결과

| Phase | GC 활동 | CPU 사용률 | 힙 사용량 |
|-------|---------|-----------|----------|
| 2 (Sync) | 0.1% | ~58% | ~108MB |
| 4 (Async) | 0.0~0.3% | ~65-75% | ~332MB (변동 있음) |
| 5 (jdbc OFF) | 0.0% | ~70-80% | ~62MB |

### Phase별 분석

**Phase 2 (Sync):** 동기 로깅으로 스레드가 콘솔 출력 완료까지 블로킹된다. 블로킹 중에는 새 요청을 처리하지 못하므로 객체 생성 자체가 적다. TPS가 14 수준이었기 때문에 힙에 부담을 줄 만큼의 객체가 생성되지 않았고, GC 활동도 거의 관찰되지 않았다.

**Phase 4 (Async):** Disruptor 큐가 비동기로 처리하지만, 큐 포화로 back-pressure가 발생했다. TPS가 18 수준이었기 때문에 객체 생성률이 GC를 유발할 수준에 도달하지 못했다. 다만 힙 사용량(~332MB)은 Phase 2보다 높았는데, Ring Buffer에 로그 이벤트 객체가 쌓인 것으로 보인다.

**Phase 5 (jdbc OFF):** TPS 2,429로 초당 2,000건 이상을 처리하므로 CPU 사용률이 Phase 2보다 약 20% 높았다. Phase 2에서 CPU가 낮았던 것은 스레드가 I/O 대기 상태에 있었기 때문이다.

### 관찰된 병목

이 실험 조건에서 TPS를 떨어뜨린 것은 GC가 아니라 콘솔 출력(System.out)의 동기 I/O 블로킹이었다. 모든 Phase에서 GC 활동은 미미했고, 힙 톱니(sawtooth) 패턴도 관찰되지 않았다.

---

## 분석 요약

1. **로그량에 따라 TPS 영향이 크게 달라졌다.** jdbc.resultset(요청당 2,500+줄)에서는 TPS가 99.4% 감소했고, jdbc.sqltiming(요청당 1~2줄)에서는 21% 감소했다.

2. **이 실험에서 GC는 병목이 아니었다.** VisualVM 프로파일링 결과, GC 활동은 모든 Phase에서 미미했다. `System.out`의 `synchronized` 블록에서 스레드가 블로킹되는 것이 주된 성능 저하 요인이었다.

3. **로그량이 극단적인 조건에서는 Sync/Async, Text/JSON 간 차이가 작았다.** 반대로 로그량이 적정한 2차 실험에서도 세 설정 간 차이는 오차 범위였다.

4. **jdbc.resultset OFF로 TPS가 Baseline의 97.7%까지 회복되었다.** Phase 4→5 전환은 로깅 설정 한 줄 변경이었지만, TPS 변화폭은 아키텍처 전환(Phase 2→4)보다 수백 배 컸다.
