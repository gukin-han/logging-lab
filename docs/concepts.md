# 핵심 개념 정리

이 실험에서 등장한 개념들을 깊이 있게 정리한다.

---

## 1. System.out의 synchronized 블로킹

Console Appender가 최종적으로 호출하는 `System.out`은 `java.io.PrintStream` 인스턴스다.

```java
// java.io.PrintStream 내부 구현
public void println(String x) {
    synchronized (this) {   // ← JVM 전체에서 하나의 lock
        print(x);
        newLine();
    }
}
```

### 왜 문제인가

- `synchronized (this)` — `this`는 `System.out` 싱글턴 객체
- Tomcat 워커 스레드 200개가 **하나의 lock**을 놓고 경합
- lock을 획득한 스레드만 쓰기 가능, 나머지는 **BLOCKED 상태**로 대기
- 이건 Log4j2의 문제가 아니라 **Java 표준 라이브러리(PrintStream)의 설계**

### 실험에서의 영향

Phase 2에서 TPS가 14.6으로 폭락한 직접적 원인이다. 스레드가 로그를 쓰는 동안 다음 요청을 처리하지 못하고, 다른 스레드들도 lock 대기 상태에 빠진다.

---

## 2. LMAX Disruptor와 Ring Buffer

Log4j2의 `AsyncLogger`는 내부적으로 LMAX Disruptor 라이브러리를 사용한다.

### Ring Buffer란

고정 크기의 **원형 배열(circular array)** 자료구조다. 배열의 끝에 도달하면 다시 처음으로 돌아간다.

```
인덱스:  [0] [1] [2] [3] [4] [5] [6] [7]
          ↑                           ↑
       consumer                    producer
       (로깅 스레드)              (Tomcat 워커 스레드)
```

- **producer**: Tomcat 워커 스레드가 로그 이벤트를 Ring Buffer에 넣음
- **consumer**: 별도의 로깅 스레드가 Ring Buffer에서 꺼내서 Console에 쓰기
- **크기**: Log4j2 기본값 262,144 슬롯 (2^18)

### 일반 BlockingQueue와의 차이

| | ArrayBlockingQueue | Disruptor Ring Buffer |
|---|---|---|
| 동기화 방식 | lock (`ReentrantLock`) | **lock-free** (CAS 연산) |
| 메모리 할당 | enqueue마다 객체 생성 가능 | 슬롯을 **미리 할당**, 재사용 |
| 캐시 친화성 | 낮음 (linked 구조) | 높음 (연속 메모리 배치) |
| 처리량 | 수십만/초 | **수백만~수천만/초** |

CAS(Compare-And-Swap)는 CPU 레벨의 원자적 연산으로, lock 없이도 동시성을 보장한다. 이 덕분에 Disruptor는 lock 경합 없이 초고속 이벤트 전달이 가능하다.

### 그럼에도 한계가 있는 이유

이 실험에서 Disruptor가 별 효과를 못 본 이유는 **큐 자체의 속도가 아니라 소비자(Console I/O)가 병목**이기 때문이다. 아무리 빠른 큐라도 소비자가 느리면 결국 가득 찬다.

---

## 3. Back-pressure

### 정의

생산 속도가 소비 속도를 초과할 때, 시스템이 생산자에게 **"느려져라"**라고 압력을 가하는 메커니즘이다.

### 이 실험에서의 동작

```
Tomcat 워커 스레드 (생산)                    로깅 스레드 (소비)
    ↓                                           ↓
 요청당 2,500 이벤트 생성    →    Ring Buffer    →    Console에 쓰기 (느림)
 100 VUser × 2,500 = 250,000/초              262,144 슬롯
```

1. 100개 VUser가 동시에 요청 → 초당 수십만 로그 이벤트 생성
2. Ring Buffer 262,144 슬롯이 수초 만에 가득 참
3. **큐가 가득 차면** 생산자(Tomcat 워커 스레드)는 `enqueue`에서 **spin-wait 또는 블로킹**
4. 결과적으로 비동기임에도 **동기처럼 동작**

### 핵심

비동기 = 무한히 빠른 것이 아니다. **소비 속도 < 생산 속도**이면 결국 back-pressure가 걸리고, 생산자도 멈춘다. Phase 4에서 TPS가 18.3에 그친 이유다.

---

## 4. AsyncLogger vs AsyncAppender

Log4j2에는 비동기 로깅 구현이 **두 가지** 있다. 이름이 비슷하지만 내부 동작이 완전히 다르다.

### AsyncAppender

```xml
<Async name="AsyncConsole">
    <AppenderRef ref="Console"/>
</Async>
```

- `java.util.concurrent.ArrayBlockingQueue` 사용
- lock 기반 동기화
- Appender 레벨에서 비동기 처리
- 별도 의존성 불필요

### AsyncLogger

```xml
<AsyncLogger name="jdbc.resultsettable" level="debug">
    <AppenderRef ref="Console"/>
</AsyncLogger>
```

- **LMAX Disruptor** 사용 (lock-free)
- Logger 레벨에서 비동기 처리
- `com.lmax:disruptor` 의존성 필요
- 로그 이벤트가 Disruptor를 거쳐 Appender에 전달

### 비교

| | AsyncAppender | AsyncLogger |
|---|---|---|
| 내부 큐 | ArrayBlockingQueue (lock) | Disruptor Ring Buffer (lock-free) |
| 성능 | 보통 | **훨씬 빠름** |
| 설정 위치 | `<Appenders>` 안에 | `<Loggers>` 안에 |
| 의존성 | 없음 | disruptor JAR 필요 |

이 실험에서는 AsyncLogger를 사용했다. AsyncAppender를 썼다면 lock 경합까지 더해져서 성능이 더 나빴을 것이다.

---

## 5. log4jdbc의 프록시 패턴

log4jdbc는 실제 JDBC 드라이버를 **래핑(wrapping)** 하는 프록시 드라이버다.

### 동작 구조

```
Application
    ↓
DriverSpy (log4jdbc 프록시)
    ├── 실제 JDBC 호출 → H2 Driver → H2 DB
    └── 로그 출력 → SLF4J → Log4j2 → Console
```

### 설정에서의 래핑

```properties
# 원래 H2 직접 연결
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver

# log4jdbc가 H2를 래핑
spring.datasource.url=jdbc:log4jdbc:h2:mem:testdb
spring.datasource.driver-class-name=net.sf.log4jdbc.sql.jdbcapi.DriverSpy
```

URL에 `log4jdbc:`를 끼워 넣으면, 모든 JDBC 호출이 DriverSpy를 거친다.

### 로거 종류별 로그량

| 로거 이름 | 로그 내용 | 요청당 로그량 |
|-----------|----------|-------------|
| `jdbc.sqlonly` | SQL문 (파라미터 포함) | 1줄 |
| `jdbc.sqltiming` | SQL문 + 실행 시간 | 1~2줄 |
| `jdbc.resultsettable` | 결과를 표 형태로 출력 | 수십 줄 |
| `jdbc.resultset` | `ResultSet.getXxx()` 호출마다 | **수천 줄** |

`jdbc.resultset`이 요청당 2,500+줄을 생성하는 이유: `ResultSet.next()` 500번 × 컬럼 5개의 `getString()`/`getLong()` = 매 호출마다 1줄씩 로그.

### 왜 위험한가

개발 환경에서 디버깅용으로 켜 놓은 `jdbc.resultset`이 운영 환경에 그대로 배포되면, **코드 변경 없이 로그만으로 시스템이 마비**될 수 있다. 이 실험의 Phase 2가 바로 그 상황이다.

---

## 6. stdout과 File Descriptor

### fd(File Descriptor)란

OS가 열린 파일/소켓/파이프 등을 식별하기 위해 부여하는 **정수 번호**다.

| fd | 용도 |
|----|------|
| 0 | stdin (표준 입력) |
| 1 | **stdout (표준 출력)** |
| 2 | stderr (표준 에러) |

### Console Appender의 I/O 경로

```
Log4j2 Console Appender
    ↓
System.out (PrintStream)
    ↓
OutputStreamWriter → BufferedOutputStream (8KB 버퍼)
    ↓
FileOutputStream (fd=1)
    ↓
write() syscall — user space → kernel space 전환
    ↓
커널 버퍼 → 터미널/콘솔 디바이스
```

### 왜 File Appender가 Console보다 빠른가

| | Console (stdout) | File Appender |
|---|---|---|
| 버퍼링 | PrintStream 8KB 버퍼 | **Log4j2가 자체 버퍼** 관리 |
| flush 제어 | `println()`마다 auto-flush | 설정으로 제어 가능 (`immediateFlush=false`) |
| I/O 대상 | 터미널 디바이스 (느림) | 디스크 (OS 페이지 캐시 활용, 빠름) |
| lock | `synchronized` (전역 lock 1개) | Appender별 독립 lock 가능 |

운영 환경에서 Console Appender를 쓰는 것은 성능상 좋지 않다. 다만 **컨테이너(Docker/K8s) 환경**에서는 stdout으로 출력한 로그를 로그 수집기(Fluentd, Filebeat 등)가 가져가는 패턴이 표준이라, Console 출력이 불가피한 경우도 있다.
