# AI triage (triage-worker)

> Trạng thái: **Approved** · Cập nhật: 2026-09-30 (DR-104: bố cục Clean Architecture) · DOC-24
> Phụ thuộc: [DOC-03](../01-product/requirements.md) FR-09, [DOC-15](../05-data/ops-and-insight-model.md) §4.3, §4.5, §6, [DOC-16](../05-data/data-quality-rules.md), [DOC-17](../05-data/db-roles-and-grants.md), [DOC-18](../05-data/data-lifecycle.md) §4, [DOC-20](etl-streaming.md) §6.1, [DOC-22](dlq-and-replay.md), [DOC-23](analytics.md) §9–10, §12.2, [DOC-28](observability.md), [DOC-30](error-handling.md), [DOC-33](../07-api/sse-events.md), [ADR-0018](../04-adr/0018-decision-model-port.md), [ADR-0019](../04-adr/0019-code-owned-automation-thresholds.md), [DR](../00-decision-register.md) (DR-36, 37, 38, 60, 72, 73, 74)
> Người dùng chính: P6-01…P6-12, DOC-36 (Ops console: DLQ, ticketing), DOC-42 (RB-04, RB-08), [EXP-06](../10-testing/experiments/EXP-06-ai-decision-quality.md), EXP-08

## 1. Mục đích và phạm vi

`triage-worker` là app Spring Boot riêng (ADR-0014). Nó đọc việc từ Postgres, hỏi mô hình quyết định (Jev qua cổng `DecisionModel`), rồi ghi kết quả về Postgres. Nó có bốn use case:

| Use case | Việc | Nguồn việc | Kết quả | FR | Cờ |
| --- | --- | --- | --- | --- | --- |
| `DLQ` | Phân loại dead letter và quyết định auto-replay | `ops.dead_letter` `NEW` | `category`, `severity`, confidence; trạng thái kế tiếp; `replay_request` tự động | FR-09.1–09.3 | `triage.dlq.enabled`, `triage.auto-replay.enabled` |
| `TICKETING` | Phân loại bất thường ticketing | `insight_ticketing_anomaly` `PENDING` | `category`, `severity`; cập nhật alert | FR-09.4 | `triage.ticketing.enabled` |
| `DISRUPTION` | Xác suất lỗi dữ liệu và nguyên nhân khả dĩ | `insight_service_disruption` `PENDING` | `data_issue_probability`, `likely_cause`; có thể đổi audience alert | FR-09.5 | `triage.disruption.enabled` |
| `DISPATCH` | Gợi ý điều phối cho episode bunching | `insight_bus_bunching` `PENDING` | Một dòng `insight_dispatch_suggestion` | FR-09.6 | `triage.dispatch.enabled` |

Ngoài phạm vi:

- Phát hiện episode và anomaly (DOC-23). triage-worker chỉ làm giàu.
- Thực thi replay (etl-batch, DOC-22). triage-worker chỉ tạo `replay_request`.
- Tạo `alert_event`. triage-worker chỉ cập nhật alert đã có; cảnh báo theo severity DLQ đi qua Prometheus (§10).

## 2. Nguyên tắc

1. **Mô hình chỉ phán đoán, code quyết định** (ADR-0019). Jev trả nhãn và confidence. Ngưỡng, danh sách category được tự động, giới hạn số lần và điều kiện an toàn nằm trong code và cấu hình.
2. **AI lỗi không chặn gì cả** (FR-09.7). ETL và analytics không gọi triage-worker và không chờ nó. Kết quả chưa có thì cột để `NULL`, UI hiện "Unclassified" hoặc "Not yet classified".
3. **Không gửi dữ liệu cá nhân** (FR-09.10, DR-60). State được dựng từ danh sách trường cho phép và kiểm lại bằng `PiiGuard` trước mỗi lời gọi (§12).
4. **Idempotent và chịu được nhiều replica.** Lấy việc bằng `FOR UPDATE SKIP LOCKED` và lease (DR-37). Mọi câu ghi kết quả có điều kiện trạng thái nguồn; ghi 0 dòng thì bỏ kết quả.
5. **Có thể tái lập.** Mỗi kết quả lưu `model_version`. Gợi ý điều phối lưu cả `state_snapshot`. `FakeDecisionModel` cho kết quả tất định để test, CI, demo offline và làm baseline của EXP-06.

## 3. Spike S-01 và phương án đã chốt cho từng kết quả

S-01 (tối đa nửa ngày, đầu P6) kiểm ba điểm của DR-36. Mỗi điểm đã có phương án chốt cho mọi kết quả, nên triển khai không phải chờ quyết định mới.

| # | Câu hỏi | Nếu có | Nếu không |
| --- | --- | --- | --- |
| 1 | Response có phiên bản model (field hay header)? | `model_version = "jev:<giá trị>"` | `model_version = "jev@<sdk-version>"`, ví dụ `jev@0.2.0` (đọc từ `TypeSafeClient.class.getPackage().getImplementationVersion()`, fallback hằng số build) |
| 2 | API batch của SDK dùng được với `Choice` + `Score` trên nhiều state? | Vẫn gọi từng state ở P6. Batch chỉ là tối ưu, không cần cho FR-09.1 (§11.3); ghi kết quả đo vào EXP-06 §10 | Gọi từng state (DR-37) |
| 3 | Mã lỗi rate limit và hết quota, lớp exception tương ứng | Ánh xạ vào bảng §11.2 theo lớp exception | Ánh xạ theo HTTP status trong exception của SDK: 429 → `RATE_LIMITED`; 402 hoặc 429 có thông điệp chứa `quota` → `QUOTA_EXHAUSTED` |
| 4 | Thang giá trị của `Score` (chỉ số mức hay [0,1]) | Không ảnh hưởng: mức được tính từ xác suất từng mức (§4.3) | Như cột trái |

Kết quả S-01 được ghi thành mục "Kết quả spike S-01" trong DR-36 (giống cách S-02 được ghi ở DR-01), kèm một response mẫu đã che API key. Response mẫu này được copy thành fixture `backend/triage-worker/src/contractTest/resources/jev/response-*.json` cho `JevContractTest` (DOC-44 §9.4), cùng các schema request `schemas/jev/request-*.json` suy từ request thật của S-01.

## 4. Thành phần

### 4.1 Cấu trúc package

Module Gradle `triage-worker`, package gốc `dev.pti.triage`. Mỗi feature chia tầng theo Clean Architecture (DOC-49, ADR-0032); cột "Tầng" cho biết lớp nằm ở package con nào của feature.

| Feature | Nội dung | Tầng (DOC-49) |
| --- | --- | --- |
| `model` | Cổng `DecisionModel` | `application.port` |
| `model` | `Question`, `Answer`, `Decision`, exception, `ModelVersion` | `domain` |
| `model` | `JevDecisionModel`, `JevQuestionMapper`, `JevErrorMapper` | `adapter.out.jev` |
| `model` | `FakeDecisionModel` và bảng luật (§15) | `adapter.out.fake` |
| `model` | `DisabledDecisionModel` | `adapter.out.disabled` |
| `loop` | `EnrichmentLoop`, `LeaseSweeper`, `LoopPauser` | `application` |
| `loop` | `WorkQueue` (interface claim/release/apply) | `application.port` |
| `dlq` | `DlqDecisionTable`, `AutoReplayGuard` | `domain` |
| `dlq` | `DlqStateBuilder`, `DlqQuestions` | `application` (ngoại lệ X-01: cây JSON Jackson) |
| `dlq` | `DlqWorkQueue` | `adapter.out.jdbc` |
| `dlq` | `AutoReplayScheduler` | `adapter.in.scheduling` |
| `ticketing`, `disruption`, `dispatch` | `*StateBuilder`, `*Questions` | `application` (ngoại lệ X-01) |
| `ticketing`, `disruption`, `dispatch` | `*WorkQueue`, `*ResultWriter` | `adapter.out.jdbc` (sau port ở `application.port`) |
| `health` | `SourceHealthClient` | port ở `application.port`, hiện thực ở `adapter.out.jdbc` |
| `guard` | `PiiGuard` | `application` (ngoại lệ X-01) |
| `flags` | `RuntimeFlagRefresher` (dùng chung với `common`, DR-19) | `adapter.in.scheduling`, đọc cờ qua port |
| `events` | `UiEventPublisher` (Kafka `pti.events.ui`, DOC-33) | port ở `application.port`, hiện thực `KafkaUiEventPublisher` ở `adapter.out.kafka` |
| `config` (gốc `dev.pti.triage.config`) | `TriageProperties` (`@ConfigurationProperties("pti.triage")`), bean Resilience4j, executor, bean use case | `config` |

Ví dụ tên đầy đủ: `dev.pti.triage.model.application.port.DecisionModel`, `dev.pti.triage.model.adapter.out.jev.JevDecisionModel`, `dev.pti.triage.dlq.domain.DlqDecisionTable`.

ArchUnit: ngoài A-11…A-18 (DOC-44 §3.3), thêm luật riêng: chỉ `..model.adapter.out.jev..` được import `org.springaicommunity.typesafe..`; `application` của mọi feature chỉ phụ thuộc cổng `DecisionModel`, không phụ thuộc adapter nào của `model`.

### 4.2 Cổng `DecisionModel` (ADR-0018)

```java
package dev.pti.triage.model.application.port;   // DecisionModel
// UseCase, Question, Answer, Decision: dev.pti.triage.model.domain (DOC-49 §11.3)

public interface DecisionModel {
  /** One state, several typed questions, one remote call. Never retries on its own. */
  Decision decide(UseCase useCase, JsonNode state, Map<String, Question> questions);
  String provider();          // "jev" | "fake" | "disabled"
  boolean enabled();          // false only for DisabledDecisionModel
}

public enum UseCase { DLQ, TICKETING, DISRUPTION, DISPATCH }

public sealed interface Question {
  record Choice(String instructions, List<Option> options) implements Question {}
  record Score(String question, List<String> levels) implements Question {}
  record Noul(String instructions, String whenTrue, String whenFalse) implements Question {}
  record Option(String label, String description) {}
}

public sealed interface Answer {
  record Choice(String label, double confidence, Map<String, Double> probabilities) implements Answer {}
  /** level is 0-based; confidence is the model's confidence in that level. */
  record Score(double value, int level, double confidence, Map<String, Double> probabilities) implements Answer {}
  record Noul(double probability) implements Answer {}
}

public record Decision(Map<String, Answer> answers, String modelVersion, Duration latency) {
  public Answer.Choice choice(String key) { … }   // throws InvalidDecisionException when missing or wrong type
  public Answer.Score score(String key) { … }
  public Answer.Noul noul(String key) { … }
}
```

- `state` là `JsonNode` kiểu object. Adapter Jev chuyển nó thành chuỗi JSON gọn (`ObjectMapper` với `ORDER_MAP_ENTRIES_BY_KEYS`) rồi truyền vào `systemOne(String state, …)`. State luôn là object nên không vướng lỗi 422 của state kiểu số hay boolean (DR-36).
- `questions` dùng `LinkedHashMap` để thứ tự câu hỏi ổn định (có ích cho log và fixture).
- `Decision.latency` đo quanh lời gọi SDK, không gồm thời gian chờ bulkhead hay rate limiter.

### 4.3 Adapter

**`JevDecisionModel`**

- Bọc một `TypeSafeClient` duy nhất: `TypeSafeClient.builder().baseUrl(p.jev().baseUrl()).apiKey(env TYPESAFE_API_KEY).maxRetries(p.jev().sdkRetries()).timeout(p.jev().httpTimeout()).build()`. Tên phương thức builder chính xác được xác nhận ở S-01; nếu SDK không cho tắt retry thì đặt về mức thấp nhất SDK cho phép và ghi vào S-01. `sdk-retries` mặc định `0`: mọi retry do vòng xử lý quyết định (§5.4), không chồng lên nhau.
- `JevQuestionMapper` đổi `Question` sang kiểu của SDK:
  - `Choice` → `Choice.builder().instructions(i)` rồi `.option(label, description)` cho từng option.
  - `Score` → `Score.of(question, levels…)`.
  - `Noul` → `Noul.builder().instructions(i).whenTrue(t).whenFalse(f).build()`.
- Đọc kết quả:
  - `Choice`: `label = r.choiceValue(k)`, `confidence = r.choice(k).confidence()`, `probabilities` từ response (map label → xác suất).
  - `Score`: `value = r.scoreValue(k)`, `probabilities` theo từng mức. **Mức = `round(Σ i · p_i)`** với `i` là chỉ số mức 0-based. Cách này không phụ thuộc thang của `value` (điểm 4 của S-01) và đúng với "làm tròn giá trị liên tục" của DR-36. `confidence` = confidence của Score. Nếu response không có xác suất theo mức thì dùng `round(value)` khi `value ∈ [0, n−1]`, `round(value · (n−1))` khi `value ∈ [0, 1]` (S-01 ghi thang nào).
  - `Noul`: `probability = r.noulValue(k)`.
- Kiểm kết quả (`InvalidDecisionException` nếu sai): mọi câu hỏi có câu trả lời; label của `Choice` thuộc danh sách option; mọi xác suất và confidence trong `[0, 1]` và không phải `NaN`.
- Exception của SDK và `java.net` được `JevErrorMapper` đổi sang exception của cổng (§11.2).

**`FakeDecisionModel`**: tất định, không gọi mạng, dựa trên chính state (§15). `latency` giả lập là 5 ms. `modelVersion = "fake@2026.09"`.

**`DisabledDecisionModel`**: `enabled() = false`; `decide` ném `IllegalStateException` (không bao giờ được gọi vì vòng xử lý kiểm `enabled()` trước, §14).

Chọn adapter bằng `pti.triage.provider` (`jev` \| `fake` \| `disabled`), qua `@ConditionalOnProperty`. `provider=jev` mà thiếu `TYPESAFE_API_KEY` → app không khởi động được (`ConfigurationException`, fail fast).

### 4.4 Exception

```java
package dev.pti.triage.model.domain;

/** Jev or the network is unavailable; the record is fine. */
public final class DecisionModelUnavailableException extends TransientInfraException {
  public enum Reason { TIMEOUT, SERVER_ERROR, NETWORK, RATE_LIMITED, QUOTA_EXHAUSTED,
                       CIRCUIT_OPEN, BULKHEAD_FULL, LOCAL_RATE_LIMIT }
  public Reason reason();
}

/** Jev refused this state (HTTP 400/422) or answered something unusable. Counts as an attempt. */
public sealed class DecisionFailedException extends RuntimeException
    permits DecisionModelRejectedException, InvalidDecisionException { … }

/** 401/403/404, missing key, PII found in a state: a bug or misconfiguration. */
public final class DecisionModelConfigException extends FatalException { … }
public final class PiiViolationException extends FatalException { … }
```

- `DecisionFailedException` không kế thừa `DataException` vì `DataException` mang `DlqStage` của pipeline ETL (DOC-30 §1), không có nghĩa ở đây. Vòng xử lý bắt nó tường minh; `pti_errors_total` vẫn ghi `kind="data"` (DOC-30 §2, cột triage-worker).
- Cách mỗi loại được xử lý: §11.2 và §19.

## 5. Vòng xử lý chung

### 5.1 `EnrichmentLoop`

Mỗi use case có một `EnrichmentLoop` chạy trên một virtual thread riêng (Spring `SmartLifecycle`, dừng mềm khi shutdown). Vòng này dùng chung code, chỉ khác `WorkQueue`:

```java
interface WorkQueue<T extends WorkItem> {
  UseCase useCase();
  List<T> claim(int limit);                  // short transaction, SKIP LOCKED, sets lease
  JsonNode buildState(T item);               // read-only queries, outside any write transaction
  Map<String, Question> questions(T item);
  ApplyResult apply(T item, Decision d);     // one transaction, guarded UPDATE; LEASE_LOST if 0 rows
  void release(T item, Failure f);           // one transaction, guarded UPDATE
  int skipStale();                           // PENDING/NEW items past max-age → SKIPPED (insight only)
  int skipAllPending();                      // flag off → SKIPPED (insight only)
}
```

```text
loop:
  if paused (circuit open, quota, fatal, provider disabled): sleep until resume; continue
  if flag(useCase) is off:
     insight: skipAllPending(); DLQ: nothing (records stay NEW, FR-09.9)
     sleep poll-interval; continue
  skipStale()
  items = claim(batchSize)
  if items empty: sleep poll-interval; continue
  run items in parallel (semaphore = parallelism(useCase)), each:
     state = buildState(item); PiiGuard.assertClean(state)
     try   d = resilientCall(useCase, state, questions(item))           // §11
           r = apply(item, d)                                           // §6–9
     catch DecisionModelUnavailableException e → release(item, TRANSIENT(e.reason))
     catch DecisionFailedException e          → release(item, COUNTED(e))
     catch DecisionModelConfigException | PiiViolationException e → release(item, FREE); pauseAll(FATAL); break
  wait for all; if items.size() == batchSize: continue immediately; else sleep poll-interval
```

| Use case | `batchSize` | `parallelism` | `max-age` (§5.5) |
| --- | --- | --- | --- |
| `DLQ` | 50 | 8 | không có |
| `TICKETING` | 20 | 2 | 24 h |
| `DISRUPTION` | 20 | 2 | 30 phút |
| `DISPATCH` | 20 | 4 | 5 phút |

Bulkhead `decision-model` (8 lời gọi đồng thời) dùng chung cho cả bốn vòng, nên tổng lời gọi không vượt 8 dù tổng `parallelism` là 16. Gợi ý điều phối cần nhanh nhất (demo bước 1), nên `DISPATCH` có `parallelism` cao hơn hai vòng insight còn lại.

### 5.2 Lấy việc (claim)

DLQ (DR-37):

```sql
WITH picked AS (
  SELECT id FROM ops.dead_letter
  WHERE status = 'NEW'
    AND triage_attempts < :maxAttempts
    AND (triage_lease_until IS NULL OR triage_lease_until < now())   -- lease column doubles as "not before" (§5.4)
  ORDER BY created_at
  LIMIT :limit
  FOR UPDATE SKIP LOCKED)
UPDATE ops.dead_letter d
SET status = 'TRIAGING', triage_lease_until = now() + :lease, updated_at = now()
FROM picked
WHERE d.id = picked.id
RETURNING d.id, d.source, d.stage, d.rule_id, d.error_class, d.error_message, d.raw_payload, d.edited_payload,
          d.kafka_timestamp, d.business_key, d.auto_replay_count, d.replay_count, d.last_replay_at,
          d.triage_attempts, d.created_at;
```

Insight (ví dụ disruption; bunching và ticketing tương tự, trên bảng của chúng):

```sql
WITH picked AS (
  SELECT id FROM insight.insight_service_disruption
  WHERE enrichment_status = 'PENDING'
    AND enrichment_attempts < :maxAttempts
    AND (enrichment_lease_until IS NULL OR enrichment_lease_until < now())
  ORDER BY created_at
  LIMIT :limit
  FOR UPDATE SKIP LOCKED)
UPDATE insight.insight_service_disruption t
SET enrichment_status = 'IN_PROGRESS', enrichment_lease_until = now() + :lease
FROM picked
WHERE t.id = picked.id
RETURNING t.*;
```

- Transaction claim ngắn (READ COMMITTED, `statement_timeout = 5s`) và commit trước khi gọi Jev (DR-37). Không có transaction nào mở trong lúc gọi mạng.
- Index một phần `…_enrich_idx … WHERE enrichment_status = 'PENDING'` và `dead_letter_triage_queue_idx … WHERE status = 'NEW'` (DOC-15) phục vụ câu này; điều kiện lease lọc trên số dòng nhỏ.
- `ORDER BY created_at`: việc cũ trước. Riêng `DISPATCH` và `DISRUPTION` đã có `max-age` nên việc quá cũ bị bỏ trước khi claim (§5.5).

### 5.3 Ghi kết quả có điều kiện

Mọi câu ghi kết quả có dạng `UPDATE … WHERE id = :id AND status = 'TRIAGING'` (DLQ) hoặc `AND enrichment_status = 'IN_PROGRESS'` (insight). Cập nhật 0 dòng nghĩa là lease đã hết và dòng đã bị trả về hàng đợi, bị replay, bị người vận hành xử lý, hoặc bị analytics reset (DOC-23 §9.6). Khi đó vòng xử lý **bỏ kết quả**, tăng `pti_triage_calls_total{outcome="ok"}` như thường và `pti_triage_lease_lost_total{use_case}`, log `INFO` `Result dropped: lease lost`. Không có gì cần hoàn tác vì transaction ghi chưa làm gì khác.

### 5.4 Trả việc (release) và backoff

Cột lease được dùng lại làm mốc "không nhận trước thời điểm". Như vậy không cần thêm cột, và bảng ticketing (không có `updated_at`) vẫn làm được backoff.

| Loại lỗi | `attempts` | Lease mới (mốc nhận lại) | Action log (DLQ) |
| --- | --- | --- | --- |
| `COUNTED` (Jev lỗi server, timeout, mạng, từ chối state, kết quả không hợp lệ, lease hết hạn) | `+1` | `now() + least(retry-backoff · 2^(n−1), 15 phút)`, `n` = attempts mới | `TRIAGE_FAILED`, `details = {reason, attempt}` |
| `TRANSIENT` do chính worker từ chối (circuit open, bulkhead đầy, rate limiter cục bộ) hoặc Jev rate limit | giữ nguyên | `now() + 30 s` | không ghi (tránh làm đầy log khi circuit mở) |
| `QUOTA_EXHAUSTED` | giữ nguyên | `now() + quota-pause` | không ghi |
| `FREE` (worker dừng vì lỗi fatal) | giữ nguyên | `NULL` | không ghi |

`TIMEOUT`, `SERVER_ERROR` và `NETWORK` được tính là một lần thử vì chúng có thể lặp lại với đúng record đó (ví dụ state quá lớn làm Jev chậm). Từ chối do chính worker thì không liên quan tới record nên không tính.

```sql
-- DLQ release, COUNTED
UPDATE ops.dead_letter
SET triage_attempts = triage_attempts + 1,
    status = CASE WHEN triage_attempts + 1 >= :maxAttempts THEN 'MANUAL' ELSE 'NEW' END,
    triage_lease_until = CASE WHEN triage_attempts + 1 >= :maxAttempts THEN NULL
                              ELSE now() + least(:backoff * power(2, triage_attempts), :maxBackoff) END,
    updated_at = now()
WHERE id = :id AND status = 'TRIAGING'
RETURNING status, triage_attempts;
```

- Action log của lỗi dùng actor `system:triage-worker` (lỗi hệ thống, không phải quyết định). Khi dòng chuyển thẳng sang `MANUAL` (lần thử thứ 5), cùng transaction ghi hai action log: `TRIAGE_FAILED` rồi `MANUAL_REQUIRED` (`details.reason = "max_attempts"`). Về mặt logic đây là `TRIAGING → NEW → MANUAL` của DOC-15 §4.3, gộp trong một câu lệnh.
- Insight: `enrichment_status = CASE WHEN attempts + 1 >= 5 THEN 'FAILED' ELSE 'PENDING' END`, lease như trên. `FAILED` là trạng thái cuối; UI hiện "Unclassified".

### 5.5 `LeaseSweeper` và việc quá hạn

`LeaseSweeper` chạy mỗi 30 giây với ShedLock `triage-lease-sweeper` (một replica làm):

```sql
UPDATE ops.dead_letter
SET triage_attempts = triage_attempts + 1,
    status = CASE WHEN triage_attempts + 1 >= :maxAttempts THEN 'MANUAL' ELSE 'NEW' END,
    triage_lease_until = CASE WHEN triage_attempts + 1 >= :maxAttempts THEN NULL
                              ELSE now() + least(:backoff * power(2, triage_attempts), :maxBackoff) END,
    updated_at = now()
WHERE status = 'TRIAGING' AND triage_lease_until < now() - interval '5 seconds'
RETURNING id, status;
```

- Action log `TRIAGE_FAILED` với `details.reason = "lease_expired"`, actor `system:triage-worker`. Câu tương tự cho ba bảng insight (`IN_PROGRESS → PENDING/FAILED`).
- Lease hết hạn chỉ xảy ra khi pod chết hoặc bị treo giữa claim và apply. Pod còn sống thì luôn release hoặc apply trước khi hết 2 phút (thời gian xấu nhất của một lô: 50 record ÷ 8 × (2 s timeout + 5 s chờ rate limiter) ≈ 44 s).
- Việc quá hạn (`skipStale`), mỗi vòng, trước khi claim:

| Use case | Điều kiện `SKIPPED` | Lý do |
| --- | --- | --- |
| `DISPATCH` | `status = 'CLOSED'` hoặc `created_at < now() − dispatch.max-age` | Gợi ý cho episode đã hết hoặc quá cũ vô ích với người điều phối |
| `DISRUPTION` | `status = 'CLOSED'` hoặc `created_at < now() − disruption.max-age` | Như trên; đúng cạnh `PENDING → SKIPPED` của DOC-15 §4.5 |
| `TICKETING` | `created_at < now() − ticketing.max-age` | Anomaly không đóng, nhưng phân loại sau 24 giờ không còn giá trị vận hành |

`created_at` là giờ thật (audit) nên `max-age` tính theo giờ thật, không theo đồng hồ nghiệp vụ. Khi chạy tăng tốc (DR-67), episode đóng nhanh hơn nên điều kiện `CLOSED` là điều kiện chính.

## 6. Triage DLQ

### 6.1 State

```json
{
  "task": "Classify why this record failed ingestion into a public transport data warehouse.",
  "record": {
    "source": "GTFS_RT_VEHICLE_POSITION",
    "stage": "QUALITY",
    "ruleId": "DQ-07",
    "ruleDescription": "Event time must be within the allowed clock skew of the business clock.",
    "errorClass": "DQ-07",
    "errorMessage": "event_timestamp 2026-09-29T21:04:10Z is 00:06:20 behind business time (max 00:05:00)",
    "payloadExcerpt": "{\"entity_type\":\"VEHICLE_POSITION\",\"schema_version\":2,…}",
    "payloadTruncated": false
  },
  "timing": {
    "eventTimestamp": "2026-09-29T21:04:10Z",
    "kafkaTimestamp": "2026-09-29T21:10:30Z",
    "businessTimeAtArrival": "2026-09-29T21:10:30Z",
    "ageAtArrivalSeconds": 380
  },
  "sourceHealth": { "status": "UP", "upForSeconds": 812 },
  "recentSimilar": { "sameRuleLast15m": 12, "sameSourceLast15m": 14, "sameSourceInputLast15m": 45210 },
  "replayHistory": { "replayCount": 0, "autoReplayCount": 0, "lastReplayAt": null }
}
```

| Trường | Cách lấy |
| --- | --- |
| `ruleDescription` | `DqRuleCatalog.description(ruleId)` trong `common` (tiếng Anh, một câu, lấy từ cột "Tên" và "Điều kiện" của DOC-16 §2); `null` khi không có `rule_id` |
| `errorMessage` | `PiiGuard.scrub(error_message)`, cắt 1.000 ký tự |
| `payloadExcerpt` | `edited_payload` nếu có, không thì `raw_payload` (đã qua `PiiScrubber` lúc ghi DLQ, DOC-18 §4.1), qua `PiiGuard.scrub` lần nữa, cắt `dlq.payload-excerpt-bytes` (4.096 byte, không cắt giữa ký tự UTF-8); `payloadTruncated` cho biết có cắt không. Là **chuỗi**, không parse, vì payload có thể là JSON hỏng |
| `timing.eventTimestamp` | Parse `event_timestamp` (GTFS-rt) hoặc `created_at` (ticketing) từ payload bằng `JsonPointer`; parse không được → `null` |
| `timing.businessTimeAtArrival` | `BusinessClock.businessTimeAt(kafka_timestamp)` (DR-67): giờ nghiệp vụ tương ứng với thời điểm record vào Kafka |
| `timing.ageAtArrivalSeconds` | `businessTimeAtArrival − eventTimestamp` (âm nếu record ở tương lai) |
| `sourceHealth` | `SourceHealthClient.snapshot(source)` (§6.6); nguồn không có indicator (`GTFS_STATIC`) → `{"status": "NOT_APPLICABLE"}` |
| `recentSimilar` | Hai `count(*)` trên `ops.dead_letter` theo `created_at > now() − 15m` (dùng `dead_letter_source_idx`), và `sum(records_read)` của `ops.etl_stream_batch` cùng nguồn trong 15 phút (`etl_stream_batch_started_idx`) |
| `replayHistory` | Cột của dòng |

State dài nhất khoảng 6 KB. `DlqStateBuilder` là lớp thuần (nhận dòng và các giá trị đã truy vấn), có unit test bằng snapshot JSON.

### 6.2 Câu hỏi

```java
static final Map<String, Question> DLQ_QUESTIONS = Map.of(   // LinkedHashMap in code
  "category", new Question.Choice(
    "Which category best explains why this record failed?",
    List.of(
      new Option("schema_violation", "The payload is malformed or does not match the expected schema; replaying it unchanged will fail again."),
      new Option("referential_integrity", "The record refers to a route, stop, trip or transaction that does not exist in the reference data."),
      new Option("upstream_api_error", "The source system produced wrong or implausible values, such as bad coordinates, delays or amounts."),
      new Option("transient_network", "The data is valid but arrived late or out of order because of delivery delays; replaying it later should succeed."),
      new Option("unknown", "None of the above fits, or there is not enough information to tell."))),
  "severity", new Question.Score(
    "How urgently does a data engineer need to look at this failure?",
    List.of("Informational", "Needs attention", "Urgent")));
```

Label giữ nguyên giá trị `CHECK` của DOC-15 (DR-36). Mô tả là tiếng Anh, được coi là một phần của "prompt" và có version cùng code; đổi mô tả thì ghi vào EXP-06 lần chạy tiếp theo.

### 6.3 Bảng quyết định (ADR-0019)

Sau khi có `category` (label `c`, confidence `p`) và `severity`, `DlqDecisionTable.decide(...)` (lớp thuần, không truy cập DB) áp **luật đầu tiên khớp**:

| # | Điều kiện | Trạng thái | `reason` |
| --- | --- | --- | --- |
| 1 | `c ∈ {schema_violation, unknown}` | `MANUAL` | `NOT_REPLAYABLE_CATEGORY` |
| 2 | `p < auto-replay.confirm-min-confidence` (0,5) | `MANUAL` | `LOW_CONFIDENCE` |
| 3 | `c ∈ auto-replay.categories` và `p > auto-replay.min-confidence` (0,9) và `auto_replay_count ≥ auto-replay.max-per-record` (2) | `MANUAL` | `AUTO_REPLAY_LIMIT` |
| 4a | Như 3 nhưng `auto_replay_count < 2`, cờ `triage.auto-replay.enabled` tắt | `PENDING_CONFIRM` | `AUTO_REPLAY_DISABLED` |
| 4b | Như 4a, cờ bật, `AutoReplayGuard` không cho phép (§6.4) | `PENDING_CONFIRM` | `GUARD_BLOCKED` |
| 4c | Như 4a, cờ bật, guard cho phép | `AUTO_REPLAY_SCHEDULED` | `AUTO_ELIGIBLE` |
| 5a | `c ∉ auto-replay.categories` (tức `referential_integrity`) | `PENDING_CONFIRM` | `CATEGORY_NOT_AUTO` |
| 5b | Còn lại (`0,5 ≤ p ≤ 0,9`) | `PENDING_CONFIRM` | `CONFIDENCE_MID` |

Giải thích các chỗ khác cách đọc chữ của FR-09.2/09.3:

- **`schema_violation` luôn vào `MANUAL`**, kể cả khi confidence ở vùng giữa. Replay không sửa gì thì record lại hỏng đúng chỗ cũ (replay chạy lại DQ-01), nên "xác nhận replay" là vô ích; `MANUAL` cho phép sửa payload rồi replay (UC-10). `unknown` cũng vậy vì không có cơ sở để replay.
- **`referential_integrity` có confidence cao vẫn vào `PENDING_CONFIRM`**, không auto: tuyến/trạm thiếu thường chỉ hết thiếu sau khi nạp feed GTFS mới, việc mà người vận hành biết còn worker thì không. `PENDING_CONFIRM` vẫn là hàng chờ quyết định của người, đúng tinh thần FR-09.3.
- Luật 3 đứng trước luật 4 nên `auto_replay_count` không bao giờ vượt 2 do code; DB có thêm `CHECK 0..2` làm lớp chặn cuối (DOC-15).

`reason` được ghi vào `dlq_action_log.details.reason` (chữ thường, ví dụ `"confidence_mid"`) và label `reason` của `pti_triage_auto_replay_total`.

### 6.4 `AutoReplayGuard`

Jev có thể gán nhầm `transient_network` với confidence cao cho một lỗi mà replay không thể sửa. Guard là danh sách cho phép **do code sở hữu**, chỉ gồm các rule mà (a) replay bỏ qua đúng rule đó (DOC-22 §3.2) và (b) lỗi phụ thuộc vào thời điểm đến:

| Stage / rule | Cho phép khi | Lý do |
| --- | --- | --- |
| `QUALITY` / `DQ-07` | `eventTimestamp` parse được **và** `eventTimestamp < businessTimeAtArrival` (record đến muộn, không phải ở tương lai) | Record hợp lệ đến trễ quá ngưỡng lệch giờ. Replay bỏ qua DQ-07 nên sẽ ghi được. Record có giờ ở tương lai là lỗi nguồn, replay chỉ ghi dữ liệu sai vào kho |
| `BUSINESS` / `DQ-12` | Giao dịch gốc `refund_of` **hiện đã có** trong `dw.fact_ticket_sales` (`SELECT 1 … WHERE transaction_id = :refundOf AND sale_date BETWEEN :d − 1 AND :d`) | Refund đến trước sale; sale đã tới thì replay ghi đúng. Replay bỏ qua DQ-12 nên nếu gốc vẫn chưa có thì không được auto |

Mọi stage/rule khác → không cho phép. Guard được kiểm **hai lần**: lúc quyết định (luật 4b) và ngay trước khi tạo `replay_request` trong `AutoReplayScheduler` (§6.5), vì dữ liệu kho có thể đổi giữa hai lúc.

### 6.5 Ghi kết quả

Một transaction (READ COMMITTED, `statement_timeout = 5s`):

```sql
UPDATE ops.dead_letter
SET status = :nextStatus,                          -- AUTO_REPLAY_SCHEDULED | PENDING_CONFIRM | MANUAL
    category = :category, category_confidence = :categoryConfidence,
    severity = :severity, severity_confidence = :severityConfidence,
    model_version = :modelVersion, triaged_at = now(),
    triage_lease_until = NULL, updated_at = now()
WHERE id = :id AND status = 'TRIAGING'
RETURNING id, source, status;
```

Rồi hai dòng `dlq_action_log` với actor `auto` ("Auto-triage" trên UI, DOC-37 §3):

1. `TRIAGED`, `confidence = categoryConfidence`, `details = {category, severity, severityConfidence, modelVersion, latencyMs}`.
2. `AUTO_REPLAY_SCHEDULED` / `CONFIRM_REQUESTED` / `MANUAL_REQUIRED`, `confidence = categoryConfidence`, `details = {reason}`.

- `TRIAGED` là trạng thái logic trong transaction này (DOC-15 §4.3: `TRIAGING → TRIAGED → …`). Sau commit không có dòng nào đứng ở `TRIAGED`; giá trị này được giữ trong `CHECK` và bộ lọc UI để tương thích.
- Sau commit: `pti_triage_decisions_total` (§18) và một sự kiện `dlq.changed` `UPDATED` với trạng thái cuối (DOC-33).

### 6.6 `SourceHealthClient`

- Gọi `GET {pti.triage.health.etl-url}/actuator/health/sources` mỗi 5 giây (`health.timeout` 1 s). etl-stream phải bật `management.endpoint.health.group.sources.show-components=always` và `show-details=never` (DOC-20 §6.1, DOC-29) để response có trạng thái từng component mà không lộ chi tiết.
- Ánh xạ nguồn → component: `GTFS_RT_VEHICLE_POSITION`, `GTFS_RT_TRIP_UPDATE` → `source-gtfs-rt`; `TICKETING_SALES`, `TICKETING_SALE_POINTS` → `source-ticketing`; `GTFS_STATIC` → không có. Hai component nguồn đã bao gồm điều kiện `warehouse-db` `UP` (DOC-20 §6.1).
- Mỗi nguồn giữ `upSince`: lần đầu thấy `UP` sau một trạng thái khác thì đặt `upSince = now`; thấy khác `UP`, hoặc gọi lỗi, hoặc timeout thì xóa `upSince` (**thất bại = không UP**, an toàn mặc định). `OUT_OF_SERVICE` (listener bị tạm dừng bằng cờ, DOC-20 §6.1) cũng là không UP.
- `upForSeconds = now − upSince` dùng cho state (§6.1) và cho `AutoReplayScheduler`.
- Có nhiều replica etl-stream (k3d): URL trỏ tới Service, mỗi lần gọi có thể vào pod khác (DOC-20 §6.1). Một pod lỗi có thể làm `upSince` bị đặt lại. Chấp nhận: hệ quả là auto-replay chậm hơn, không bao giờ sớm hơn.
- Metric `pti_triage_source_up{source}` (0/1).

### 6.7 `AutoReplayScheduler`

Chạy mỗi `auto-replay.scheduler-interval` (5 s), ShedLock `triage-auto-replay`:

```text
flagOn = flag(triage.auto-replay.enabled)
rows = SELECT … FROM ops.dead_letter WHERE status = 'AUTO_REPLAY_SCHEDULED' ORDER BY triaged_at LIMIT 200
for row in rows:
  if not flagOn:                            → PENDING_CONFIRM, reason auto_replay_disabled
  elif row.triaged_at < now() − max-wait:   → PENDING_CONFIRM, reason source_not_recovered
  elif not AutoReplayGuard.allows(row):     → PENDING_CONFIRM, reason guard_blocked
  elif upFor(row.source) < source-up-for:   skip (wait)
  elif requestedThisMinute ≥ max-per-minute: stop this round
  else: requestReplay(row)
```

`requestReplay` là một transaction:

```sql
UPDATE ops.dead_letter
SET status = 'REPLAY_REQUESTED', auto_replay_count = auto_replay_count + 1, updated_at = now()
WHERE id = :id AND status = 'AUTO_REPLAY_SCHEDULED' AND auto_replay_count < :maxPerRecord
RETURNING auto_replay_count;

INSERT INTO ops.replay_request (id, kind, dead_letter_id, requested_by, idempotency_key, …)
VALUES (:uuidv7, 'DLQ_RECORD', :id, 'auto', 'auto:' || :id || ':' || :autoReplayCount, …);

INSERT INTO ops.dlq_action_log (id, dead_letter_id, action, actor, confidence, details, at)
VALUES (:uuidv7, :id, 'REPLAY_REQUESTED', 'auto', :categoryConfidence,
        jsonb_build_object('replayRequestId', :requestId, 'sourceUpForSeconds', :upFor), now());
```

- UPDATE trả 0 dòng (người vận hành vừa xử lý) → rollback, bỏ qua. Vi phạm `replay_request_one_per_record` hoặc `UNIQUE (requested_by, idempotency_key)` (`23505`) → rollback, log `WARN`, dòng giữ nguyên để vòng sau xem lại.
- Chuyển về `PENDING_CONFIRM` dùng `UPDATE … WHERE status = 'AUTO_REPLAY_SCHEDULED'` và action log `CONFIRM_REQUESTED` (actor `auto`) với `details.reason`. Đây là **cạnh mới** `AUTO_REPLAY_SCHEDULED → PENDING_CONFIRM` (thêm vào DOC-15 §4.3).
- `max-per-minute` (60) chặn một đợt replay dồn dập làm quá tải etl-batch sau khi nguồn hồi phục; phần còn lại được xử lý ở các vòng sau.
- Mỗi dòng đổi trạng thái → một `dlq.changed` `UPDATED` sau commit.
- etl-batch nhận `replay_request` như request tay (DOC-22 §3). Replay lỗi lại → dòng về `NEW` và được triage lại; lần sau luật 3 chặn khi đã auto 2 lần.

### 6.8 Luồng mẫu

```mermaid
sequenceDiagram
  autonumber
  participant ES as etl-stream
  participant PG as Postgres
  participant TW as triage-worker
  participant J as DecisionModel
  participant EB as etl-batch
  ES->>PG: INSERT dead_letter NEW (DQ-07, late)
  TW->>PG: claim → TRIAGING (lease 2 min)
  TW->>J: decide(DLQ, state, {category, severity})
  J-->>TW: transient_network 0.95, severity 0 (0.88)
  TW->>PG: → AUTO_REPLAY_SCHEDULED; log TRIAGED, AUTO_REPLAY_SCHEDULED
  loop every 5 s
    TW->>ES: GET /actuator/health/sources
  end
  TW->>PG: source UP ≥ 60 s → REPLAY_REQUESTED, auto_replay_count 1, replay_request (auto)
  EB->>PG: DlqReplayJob → REPLAYED
```

## 7. Phân loại bất thường ticketing

- **State:** `{"task": "Classify an unusual ticket sales pattern at one sale point.", "anomaly": <summary>}`, với `summary` là cột JSONB của dòng (DOC-23 §9.5), đã không có dữ liệu cá nhân.
- **Câu hỏi:**

| Khóa | Loại | Nội dung |
| --- | --- | --- |
| `category` | `Choice` | `fraud_suspect` "Refunds or sales look abusive, such as many refunds of recent sales at one point." · `system_error` "The pattern looks like a technical fault, such as duplicated transactions or a broken sale point." · `promo_spike` "Sales rose because of a legitimate reason such as a promotion, an event or a service change." · `normal` "Nothing unusual for this time and place once context is considered." |
| `severity` | `Score` | "How urgently should the operations team look at this?" · `Informational`, `Needs attention`, `Urgent` |

- **Ghi kết quả** (một transaction):

```sql
UPDATE insight.insight_ticketing_anomaly
SET category = :category, category_confidence = :cc, severity = :severity, severity_confidence = :sc,
    model_version = :mv, enriched_at = now(),
    enrichment_status = 'DONE', enrichment_lease_until = NULL
WHERE id = :id AND enrichment_status = 'IN_PROGRESS' AND summary = :claimedSummary::jsonb
RETURNING id;

UPDATE ops.alert_event
SET body = body || :patch::jsonb, severity = :alertSeverity
WHERE dedup_key = 'ticketing:' || :id
RETURNING id, audience, severity, title, body;
```

- Điều kiện `summary = :claimedSummary` chặn trường hợp analytics tính lại cửa sổ trong lúc đang hỏi Jev (DOC-23 §9.6 reset về `PENDING`, nhưng nếu lần reset xảy ra rồi dòng lại được claim bởi replica khác thì dòng đó đang `IN_PROGRESS` với summary mới). 0 dòng → bỏ kết quả (§5.3).
- `patch` = `{"category", "categoryConfidence", "aiSeverity", "aiSeverityConfidence", "modelVersion"}`.
- `alertSeverity` (code sở hữu, ADR-0019):
  - `category = normal` và `categoryConfidence ≥ ticketing.normal-min-confidence` (0,8) → `0`.
  - Không thì `severityConfidence ≥ ticketing.severity-min-confidence` (0,6) → severity của mô hình.
  - Không thì giữ nguyên severity do analytics đặt (`1`).
- Alert không tồn tại (tính lại đã xóa) → chỉ cập nhật anomaly; không lỗi.
- Sau commit: `alert.updated` (DOC-33 §5.5) nếu alert được cập nhật. Không có sự kiện riêng cho anomaly; màn Ticketing đọc lại qua `alert.updated` (DOC-36).

## 8. Làm giàu gián đoạn dịch vụ

### 8.1 State

```json
{
  "task": "Judge whether a detected bus service disruption is real and what most likely caused it.",
  "route": { "routeId": "18", "shortName": "18", "routeType": "BUS", "directionId": 0, "directionName": "Northbound" },
  "episode": {
    "startedAt": "2026-09-29T21:30:00Z", "durationMinutes": 12, "status": "OPEN",
    "currentAvgDelaySeconds": 540.0, "peakAvgDelaySeconds": 610.0,
    "baselineMeanSeconds": 95.0, "baselineStddevSeconds": 60.0, "currentZScore": 7.42, "peakZScore": 8.58,
    "sampleCount": 184
  },
  "affectedStops": [ { "stopId": "56043", "name": "Nicollet Mall & 5th St" } ],
  "context": {
    "localTime": "16:30", "dayType": "WEEKDAY", "peak": true,
    "bunchingEpisodesOnRouteLast30m": 2, "otherOpenDisruptions": 1
  },
  "dataQuality": {
    "feedAgeSeconds": 8, "vehiclesReporting": 11, "vehiclesScheduled": 14, "reportingRatio": 0.79,
    "deadLettersGtfsRtLast15m": 3, "deadLetterRatioGtfsRtLast15m": 0.0001
  }
}
```

| Trường | Cách lấy |
| --- | --- |
| `route`, `affectedStops` | `dw.dim_route`, `dw.dim_stop` của feed ACTIVE; tối đa 10 trạm theo thứ tự `affected_stop_ids` |
| `context.localTime`, `dayType`, `peak` | `businessNow` theo giờ agency; `dayType` đọc thẳng từ `dw.dim_date` (cùng nguồn với `AnalyticsReferenceCache.dayType`, DOC-23 §3); `peak` = `dayType = WEEKDAY` và giờ địa phương thuộc `[06:00, 09:00)` hoặc `[15:00, 18:00)` |
| `bunchingEpisodesOnRouteLast30m`, `otherOpenDisruptions` | Đếm trên `insight_bus_bunching` (`episode_start` 30 phút) và `insight_service_disruption` (`status = 'OPEN'`, khác id) |
| `feedAgeSeconds` | `businessNow − max(event_timestamp)` của `dw.vehicle_position_latest` |
| `vehiclesReporting`, `vehiclesScheduled` | Số xe của tuyến trong `vehicle_position_latest` cập nhật trong 2 phút; số chuyến của tuyến đang chạy theo lịch (`gtfs_stop_time` của feed ACTIVE, giờ hiện tại) |
| `deadLetters…`, `deadLetterRatio…` | `ops.dead_letter` nguồn `GTFS_RT_*` trong 15 phút; tỷ lệ chia cho `sum(records_read)` của `etl_stream_batch` cùng nguồn |

Không có dữ liệu cá nhân nào trong bảng nguồn; `PiiGuard.assertClean` vẫn chạy.

### 8.2 Câu hỏi

| Khóa | Loại | Nội dung |
| --- | --- | --- |
| `dataIssue` | `Noul` | instructions "Is this disruption more likely caused by a data problem (missing, delayed or wrong vehicle data) than by real service delays?" · whenTrue "It is a data problem." · whenFalse "Buses are really delayed." |
| `likelyCause` | `Choice` | `traffic` "Road congestion or slow traffic along the route." · `vehicle_breakdown` "A vehicle broke down or was taken out of service." · `weather` "Weather conditions slowed service." · `event` "A special event or unusual crowding." · `data_issue` "The delays are an artifact of bad or missing data." · `unknown` "Not enough information to tell." |

### 8.3 Ghi kết quả

```sql
UPDATE insight.insight_service_disruption
SET data_issue_probability = :p, likely_cause = :cause, cause_confidence = :cc,
    model_version = :mv, enriched_at = now(), enrichment_status = 'DONE', enrichment_lease_until = NULL
WHERE id = :id AND enrichment_status = 'IN_PROGRESS'
RETURNING id;
```

Rồi, cùng transaction, trên alert `dedup_key = 'disruption:<id>'`:

- `p > disruption.data-issue-threshold` (0,7): `UPDATE ops.alert_event SET audience = 'ENGINEERING', body = body || :patch WHERE dedup_key = :k AND audience <> 'ENGINEERING' RETURNING id, …` (FR-09.5). Sau commit phát `alert.retracted` với audience **cũ** rồi `alert.updated` với audience mới (DOC-33 §5.6).
- Không thì chỉ `body = body || :patch` và phát `alert.updated`.
- `patch = {"likelyCause", "causeConfidence", "dataIssueProbability", "modelVersion"}`. Tiêu đề không đổi (UI ghép nguyên nhân từ `body`, DOC-37 §3.3).
- Analytics không bao giờ ghi lại `audience` (DOC-23 §10.1), nên audience đã hạ không bị ghi đè khi episode nâng severity hay đóng.

## 9. Gợi ý điều phối

### 9.1 State

```json
{
  "task": "Suggest one dispatch action for a pair of buses running too close together on the same route.",
  "route": { "routeId": "5", "shortName": "5", "directionId": 1, "directionName": "Southbound" },
  "episode": { "gapSeconds": 95, "scheduledHeadwaySeconds": 600, "gapRatio": 0.16, "minGapSeconds": 80,
               "durationSeconds": 240 },
  "leader":   { "vehicleId": "1432", "nextStopId": "12105", "stopsRemaining": 21, "delaySeconds": 380 },
  "follower": { "vehicleId": "1437", "nextStopId": "12103", "stopsRemaining": 23, "delaySeconds": 20 },
  "timeOfDay": { "localTime": "17:05", "dayType": "WEEKDAY", "peak": true },
  "demand": { "level": "HIGH", "recentTicketSales60m": 412, "typicalTicketSales60m": 290 }
}
```

- Vehicle id là mã xe công cộng của agency (không phải dữ liệu cá nhân).
- `gapSeconds = last_gap_seconds`, `gapRatio = gap / scheduled_headway`, `durationSeconds = last_evaluated_at − episode_start`.
- `leader`/`follower`: vị trí mới nhất trong `vehicle_position_latest`, `stopsRemaining` từ `gtfs_stop_time` của chuyến, `delaySeconds` từ `fact_trip_update` mới nhất của chuyến (null nếu chưa có).
- `demand` do **code** tính (không để mô hình đoán): `recentTicketSales60m` = số giao dịch `SALE` trong 60 phút tại các điểm bán có `route_id` là tuyến này; `typicalTicketSales60m` = trung bình cùng giờ, cùng `dayType` của 4 tuần trước. `level`: `LOW` nếu `recent < 0,7 · typical`, `HIGH` nếu `> 1,3 · typical`, còn lại `NORMAL`; `typical = 0` → `NORMAL`.

### 9.2 Câu hỏi

`action`: `Choice` "Which single action should the dispatcher take now?" với `hold_follower` "Hold the following bus at its next timepoint for a short time to restore spacing." · `skip_stops` "Let the leading bus skip some stops to regain its schedule." · `no_action` "Do nothing; the gap is likely to recover on its own or intervention would cost more than it helps."

### 9.3 Ghi kết quả

```sql
INSERT INTO insight.insight_dispatch_suggestion
  (id, bunching_id, route_id, action, action_confidence, state_snapshot, model_version)
VALUES (:id, :bunchingId, :routeId, :action, :confidence, :state::jsonb, :mv)   -- id = InsightIds.dispatchSuggestion(bunchingId)
ON CONFLICT ON CONSTRAINT insight_dispatch_suggestion_bunching_uk DO NOTHING
RETURNING id;

UPDATE insight.insight_bus_bunching
SET enrichment_status = 'DONE', enrichment_lease_until = NULL
WHERE id = :bunchingId AND enrichment_status = 'IN_PROGRESS'
RETURNING id;
```

- Câu UPDATE chạy trước INSERT trong code (điều kiện lease); 0 dòng → rollback. `ON CONFLICT DO NOTHING` giữ gợi ý và phản hồi cũ khi episode được tính lại cùng id (DOC-15 §6: không có khóa ngoại).
- `state_snapshot` là đúng state đã gửi (FR-09.6, audit).
- Sau commit: `dispatch.suggested` (DOC-33 §5.4). "Low confidence" do UI tính theo ngưỡng 0,6.

## 10. Alert theo severity DLQ và luật chặn cuối

triage-worker **không INSERT** `alert_event`. Cảnh báo DLQ đi qua Prometheus → Alertmanager → webhook `api` (DOC-28 §6.4), như mọi alert hạ tầng, để có dedup, silence và định tuyến chung:

| Severity DLQ | Hành động | Alert (DOC-28 §6.3) | `alert_event.type` (DOC-32 E-80) |
| --- | --- | --- | --- |
| 2 Urgent | `pti_triage_decisions_total{use_case="dlq", severity="2"}` tăng | #27 `DlqSevereRecords` (critical) | `DLQ_SEVERE` |
| 1 Needs attention | như trên, `severity="1"` | #28 `DlqNeedsAttention` (warning, ≥ 10 trong 30 phút theo nguồn) | `INFRA` |
| 0 Informational | chỉ metric và log `INFO` | — | — |

**Luật chặn cuối (FR-09.8)** phải hoạt động "bất kể Jev trả gì", kể cả khi triage-worker tắt:

- `DlqRuleClassifier` (module `common`) gán category tất định theo stage/rule, dùng đúng bảng của Fake (§15.1, cột "Category").
- etl phát `pti_dlq_rule_category_total{source, category}` ngay khi ghi dòng DLQ (DOC-22 §9), không phụ thuộc triage.
- Alert #29 `DlqUpstreamErrorBurst`: `sum by (source) (increase(pti_dlq_rule_category_total{category="upstream_api_error"}[1h])) > 50`, critical, RB-04. N = 50 nằm trong file rule Prometheus (`deploy/compose/observability/prometheus/rules/pti-triage.yml` (DOC-28 §6)), không phải property của app.

## 11. Resilience

### 11.1 Thứ tự decorator

Một bộ instance Resilience4j tên `decision-model` dùng chung cho mọi use case, bọc từ ngoài vào trong:

```text
Bulkhead (8 concurrent, max-wait 1 s)
  → RateLimiter (20 calls/s, timeout 5 s)
    → CircuitBreaker
      → TimeLimiter (2 s, cancel running future)
        → DecisionModel.decide on a virtual-thread executor
```

| Instance | Cấu hình |
| --- | --- |
| `bulkhead.decision-model` | `max-concurrent-calls: 8`, `max-wait-duration: 1s` |
| `ratelimiter.decision-model` | `limit-for-period: 20`, `limit-refresh-period: 1s`, `timeout-duration: 5s` |
| `circuitbreaker.decision-model` | `sliding-window-type: COUNT_BASED`, `sliding-window-size: 20`, `minimum-number-of-calls: 10`, `failure-rate-threshold: 50`, `slow-call-duration-threshold: 1500ms`, `slow-call-rate-threshold: 80`, `wait-duration-in-open-state: 30s`, `permitted-number-of-calls-in-half-open-state: 3`, `record-exceptions: DecisionModelUnavailableException`, `ignore-exceptions: DecisionFailedException` |
| `timelimiter.decision-model` | `timeout-duration: 2s`, `cancel-running-future: true` |

- Circuit breaker chỉ đếm lỗi hạ tầng. Jev từ chối một state cụ thể (`DecisionFailedException`) không phải dấu hiệu Jev hỏng.
- Khi circuit `OPEN`, `LoopPauser` dừng claim ở mọi vòng tới khi circuit sang `HALF_OPEN` (listener sự kiện của Resilience4j). Việc đang giữ bị release với lỗi `TRANSIENT` (§5.4). Như vậy không có vòng quay rỗng tạo hàng nghìn lần release.
- Resilience4j dùng module core (DOC-11 §3): bean tạo tay trong `ResilienceConfig`, đọc từ `resilience4j.*` qua `@ConfigurationProperties` riêng nếu starter chưa hỗ trợ Boot 4.

### 11.2 Ánh xạ lỗi

| Nguồn | Exception của cổng | Xử lý (§5.4) | `outcome` |
| --- | --- | --- | --- |
| `TimeoutException` của TimeLimiter; timeout HTTP của SDK | `Unavailable(TIMEOUT)` | `COUNTED` | `timeout` |
| HTTP 5xx | `Unavailable(SERVER_ERROR)` | `COUNTED` | `error` |
| `IOException`, `ConnectException`, DNS | `Unavailable(NETWORK)` | `COUNTED` | `error` |
| HTTP 429 | `Unavailable(RATE_LIMITED)` | `TRANSIENT` | `rejected` |
| Hết quota (S-01 §3 điểm 3) | `Unavailable(QUOTA_EXHAUSTED)` | `TRANSIENT` với lease `quota-pause`; `LoopPauser` dừng mọi vòng `quota-pause` (15 phút) | `quota` |
| `CallNotPermittedException` | `Unavailable(CIRCUIT_OPEN)` | `TRANSIENT` | `rejected` |
| `BulkheadFullException` | `Unavailable(BULKHEAD_FULL)` | `TRANSIENT` | `rejected` |
| `RequestNotPermitted` | `Unavailable(LOCAL_RATE_LIMIT)` | `TRANSIENT` | `rejected` |
| HTTP 400, 422 | `DecisionModelRejectedException` | `COUNTED` | `invalid` |
| Kết quả thiếu khóa, label lạ, số ngoài `[0,1]` | `InvalidDecisionException` | `COUNTED` | `invalid` |
| HTTP 401, 403, 404; thiếu API key | `DecisionModelConfigException` | `FREE`; dừng mọi vòng; readiness `DOWN` | `error` |
| `PiiGuard` phát hiện trường cấm | `PiiViolationException` | `FREE`; dừng mọi vòng; readiness `DOWN` | — (không gọi) |

Dừng vì lỗi fatal nghĩa là: log `ERROR` kèm `pti_error_kind=FATAL`, `pti_errors_total{kind="fatal"}` (alert #13 `FatalErrors`), health indicator `triage` → `DOWN` (readiness, không phải liveness: pod không bị restart vòng lặp). Gỡ bằng cách sửa cấu hình rồi restart pod (RB-04).

### 11.3 Năng lực

Với median ≈ 300 ms (DR-36) và 8 lời gọi đồng thời, trần lý thuyết ≈ 26 lời gọi/giây, rate limiter giới hạn ở 20/giây. 100 record DLQ mất khoảng 5 giây, dư nhiều so với 60 giây của FR-09.1. `bad-data` ở tỷ lệ 1% trên tải nền (khoảng 150 msg/s, DOC-10) sinh khoảng 1,5 dead letter/giây, dưới năng lực một replica. KEDA thêm replica khi backlog tăng (DOC-40), nhưng rate limiter và bulkhead là **theo pod**, nên tổng lời gọi tới Jev tăng theo số replica; `maxReplicaCount: 3` giữ tổng ≤ 60/giây.

## 12. PII (FR-09.10, DR-60)

`PiiGuard` (package `guard`):

- `scrub(String)`: thay email (`[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}`) bằng `[email]` và chuỗi 13–19 chữ số liền nhau (cho phép khoảng trắng hoặc `-` giữa các nhóm) bằng `[number]`. Dùng cho chuỗi tự do: `errorMessage`, `payloadExcerpt`.
- `assertClean(JsonNode)`: duyệt mọi khóa ở mọi cấp. Khóa được chuẩn hóa (chữ thường, bỏ `_` và `-`) rồi so với danh sách cấm: `customerref`, `email`, `phone`, `cardnumber`, `pan`, `token`, `password`, `secret`, `authorization`, `ipaddress`, cộng `pti.triage.guard.pii-extra-keys`. Có khóa cấm → `PiiViolationException` (lỗi lập trình: state builder chỉ dùng trường cho phép).
- `payloadExcerpt` là chuỗi nên `assertClean` không thấy khóa bên trong; ticketing payload trong DLQ đã được `PiiScrubber` bỏ `customer_ref` lúc ghi (DOC-18 §4.1), và `scrub` chạy thêm một lần.
- `insight_dispatch_suggestion.state_snapshot` lưu đúng state đã qua `assertClean`.
- Log không in state đầy đủ ở mức `INFO`; `DEBUG` in state (đã sạch) để chẩn đoán.

## 13. `model_version`

| Provider | Giá trị | Ví dụ |
| --- | --- | --- |
| `jev`, response có phiên bản (S-01) | `jev:<giá trị>` | `jev:systemone-2026-09-15` |
| `jev`, không có | `jev@<sdk-version>` | `jev@0.2.0` |
| `fake` | `fake@<phiên bản bảng luật>` | `fake@2026.09` |

Ví dụ trong DOC-32, DOC-33 và màn hình dùng `jev@0.2.0`. `model_version` luôn đi kèm mọi kết quả (DOC-15 `CHECK` cho `DONE`), và là chiều phân tích chính của EXP-06.

## 14. Cờ, provider và khởi động

| Trạng thái | DLQ | Insight |
| --- | --- | --- |
| Cờ use case tắt (`triage.<x>.enabled = false`) | Không claim; dòng giữ `NEW` (FR-09.9); người vận hành vẫn xử lý tay từ `NEW` | Mỗi vòng: `UPDATE … SET enrichment_status = 'SKIPPED' WHERE enrichment_status = 'PENDING'` (lô 500) |
| `triage.auto-replay.enabled = false` | Triage vẫn chạy; luật 4a đưa ứng viên vào `PENDING_CONFIRM`; scheduler chuyển dòng `AUTO_REPLAY_SCHEDULED` còn lại sang `PENDING_CONFIRM` | — |
| `pti.triage.provider=disabled` | Như cờ tắt với cả bốn use case; health `triage` `UP` với chi tiết `provider=disabled` | Như cờ tắt |
| Circuit `OPEN`, quota | Tạm dừng claim (§11.1) | Như DLQ |
| Fatal | Dừng hẳn tới khi restart | Như DLQ |

- Cờ đọc từ `ops.runtime_flag` mỗi 5 giây (DR-19); giá trị mặc định khi thiếu dòng: `true` cho cả năm cờ `triage.*` (seed ở V5_2).
- Bật lại cờ insight chỉ ảnh hưởng dòng mới; dòng đã `SKIPPED` không được làm lại (episode đã cũ).
- Khởi động: readiness `UP` khi datasource, Kafka producer và (với `jev`) API key đều có. Không gọi thử Jev lúc khởi động (tránh tốn quota và phụ thuộc mạng khi demo offline).

### 14.1 Chế độ đánh giá (profile `eval`, EXP-06)

Để EXP-06 đo đúng đường code chạy thật (ADR-0018), triage-worker có profile `eval`. Profile này không chạy vòng xử lý, sweeper, scheduler hay Kafka producer (các bean đó có `@Profile("!eval")`), không ghi DB, chạy một lệnh rồi thoát (mã 0 khi xong, 1 khi lỗi).

| `pti.triage.eval.command` | Đầu vào | Đầu ra (JSON Lines) |
| --- | --- | --- |
| `export-dlq` | `ops.dead_letter` có `id > eval.from-id`, tối đa `eval.limit` dòng, theo `id` | Mỗi dòng: `{"itemId", "useCase": "DLQ", "source", "stage", "ruleId", "kafka": {"topic", "partition", "offset"}, "guardAllowed", "state"}`; `state` dựng bằng `DlqStateBuilder` (§6.1), `guardAllowed` bằng `AutoReplayGuard` (§6.4) tại thời điểm export |
| `export-ticketing` | `insight.insight_ticketing_anomaly` có `computed_at ≥ eval.from-time` | `{"itemId", "useCase": "TICKETING", "trigger", "state"}` (§7) |
| `export-disruption` | `insight.insight_service_disruption` có `created_at ≥ eval.from-time` | `{"itemId", "useCase": "DISRUPTION", "routeId", "state"}` (§8) |
| `decide` | File của một lệnh export | Mỗi dòng: `{"itemId", "useCase", "provider", "modelVersion", "outcome", "latencyMs", "answers", "decision"}`. `outcome` ∈ `ok`, `failed`, `unavailable` (theo nhóm exception §4.4); `answers` giữ label/giá trị, confidence và **xác suất từng lựa chọn/mức**; `decision` là kết quả của `DlqDecisionTable` (cờ auto-replay coi như bật, guard lấy `guardAllowed`), hoặc severity/audience tính theo §7, §8. Không có `decision` khi `outcome ≠ ok` |

- `decide` đi qua cùng `DecisionModel` và chuỗi Resilience4j của §11, với `pti.triage.eval.parallelism` lời gọi đồng thời (mặc định 1, để đo độ trễ không bị tranh chấp). Lỗi `unavailable` được thử lại tối đa 3 lần cách 30 giây; hết lần vẫn ghi dòng với `outcome = unavailable`.
- State đã export là **tập dữ liệu cố định**: mọi provider được chạy trên cùng file, nên khác biệt chỉ đến từ mô hình. `PiiGuard.assertClean` chạy lúc export và lúc `decide`.
- Chạy: `docker compose run --rm -e SPRING_PROFILES_ACTIVE=eval -v ./experiments/datasets:/data triage-worker --pti.triage.eval.command=decide --pti.triage.eval.input=/data/EXP-06/dlq-states.jsonl --pti.triage.eval.output=/data/EXP-06/runs/<run>/dlq-jev.jsonl`. Runner EXP-06 gọi lệnh này qua docker SDK.

## 15. `FakeDecisionModel`

Bảng luật tất định, cũng là **baseline luật** của EXP-06. Hàm `jitter(id)` = `(hash(id) mod 5) / 100`, trừ đi từ confidence để số liệu không giống hệt nhau nhưng vẫn tái lập được.

### 15.1 DLQ

| Stage / rule | Điều kiện thêm | Category | Confidence | Severity (conf.) | Kết quả theo §6.3 |
| --- | --- | --- | --- | --- | --- |
| `DESERIALIZE`, `SCHEMA` (DQ-01) | — | `schema_violation` | 0,95 | 1 (0,8) | `MANUAL` |
| `QUALITY` DQ-03, 04, 05 | — | `referential_integrity` | 0,72 | 1 (0,7) | `PENDING_CONFIRM` |
| `QUALITY` DQ-07 | `ageAtArrivalSeconds > 0` (muộn) | `transient_network` | 0,95 | 0 (0,85) | `AUTO_REPLAY_SCHEDULED` |
| `QUALITY` DQ-07 | còn lại (tương lai) | `upstream_api_error` | 0,45 | 1 (0,7) | `MANUAL` |
| `QUALITY` DQ-06 | — | `upstream_api_error` | 0,35 | 1 (0,7) | `MANUAL` (giữ E2E-DLQ-02/03: sửa payload) |
| `QUALITY` DQ-08, 09, 10, 11; `BUSINESS` DQ-13 | — | `upstream_api_error` | 0,45 | 1 (0,7) | `MANUAL` |
| `BUSINESS` DQ-12 | — | `transient_network` | 0,93 | 0 (0,8) | `AUTO_REPLAY_SCHEDULED` nếu guard cho phép, không thì `PENDING_CONFIRM` |
| `DEDUP` DQ-02 | — | `unknown` | 0,5 | 1 (0,6) | `MANUAL` |
| `LOAD` | — | `unknown` | 0,3 | 2 (0,9) | `MANUAL`, alert #27 |

`DlqRuleClassifier` (§10) dùng cột "Category" của bảng này (với DQ-07 luôn là `upstream_api_error` vì etl không phân biệt muộn/tương lai lúc ghi).

### 15.2 Ticketing

| `trigger` | Category (conf.) | Severity (conf.) |
| --- | --- | --- |
| `REFUND_RATIO` | `fraud_suspect` (0,77) | 2 (0,69) |
| `BOTH` | `system_error` (0,65) | 1 (0,7) |
| `VOLUME` | `promo_spike` (0,72) | 1 (0,66) |

### 15.3 Disruption

| Điều kiện (đánh giá theo thứ tự) | `dataIssue` | `likelyCause` (conf.) |
| --- | --- | --- |
| `deadLetterRatioGtfsRtLast15m > 0,01` hoặc `reportingRatio < 0,5` | 0,85 | `data_issue` (0,8) |
| `bunchingEpisodesOnRouteLast30m ≥ 2` | 0,15 | `traffic` (0,65) |
| còn lại | 0,1 | `unknown` (0,5) |

### 15.4 Dispatch

| Điều kiện | `action` (conf.) |
| --- | --- |
| `gapRatio < 0,25` và `demand.level ≠ LOW` | `hold_follower` (0,82) |
| `leader.delaySeconds > 300` và `leader.stopsRemaining > 10` | `skip_stops` (0,58) |
| còn lại | `no_action` (0,55) |

## 16. Transaction và đồng thời

| Đơn vị | Transaction | Khóa / chống trùng |
| --- | --- | --- |
| Claim | Ngắn, READ COMMITTED, `statement_timeout = 5s` | `FOR UPDATE SKIP LOCKED` + lease |
| Dựng state | Không transaction ghi; truy vấn đọc riêng lẻ, `statement_timeout = 2s` | — |
| Gọi mô hình | Không transaction | Bulkhead, rate limiter |
| Apply | Một transaction, `statement_timeout = 5s` | `WHERE status = 'TRIAGING'` / `enrichment_status = 'IN_PROGRESS'` |
| Release, sweeper | Một transaction mỗi dòng (release) / mỗi lô (sweeper) | Như apply; sweeper có ShedLock |
| Auto-replay | Một transaction mỗi dòng | ShedLock `triage-auto-replay`; `WHERE status = 'AUTO_REPLAY_SCHEDULED'`; unique của `replay_request` |

- Xung đột với `api`: `TRIAGING` và `AUTO_REPLAY_SCHEDULED` không nằm trong trạng thái nguồn của thao tác tay (DOC-32 §7), nên API trả 409 `dlq-invalid-state` và hai bên không bao giờ ghi đè nhau. Muốn giành lại quyền quyết định cho các dòng đang chờ auto-replay, người vận hành tắt cờ `triage.auto-replay.enabled`; trong ≤ 10 giây scheduler chuyển chúng sang `PENDING_CONFIRM` (§6.7). Ngược lại, apply của triage-worker thấy 0 dòng nếu API đã đổi dòng `NEW` trước khi claim (không xảy ra với dòng đã claim).
- Xung đột với etl: `DeadLetterWriter` ở chế độ replay đặt lại dòng về `NEW` và xóa lease (DOC-22 §1.3); apply của triage sau đó thấy 0 dòng và bỏ kết quả cũ. Dòng sẽ được triage lại với lỗi mới.
- Xung đột với analytics: DOC-23 §12.2.
- Hikari: `maximum-pool-size = 4` (DOC-29 §3.1). Mỗi kết nối chỉ được giữ trong vài mili giây (claim, từng truy vấn dựng state, apply), không bao giờ trong lúc gọi mạng, nên 4 kết nối đủ cho 20 lời gọi/giây. 3 pod × 4 = 12, dưới giới hạn 20 của `triage_writer`.
- Sự kiện UI phát **sau commit** (DR-42) bằng `TransactionSynchronization.afterCommit`. Pod chết giữa commit và publish thì mất sự kiện; UI tự đồng bộ lại (DOC-26).

## 17. Cấu hình

`TriageProperties` (`pti.triage.*`), có `@Validated`:

| Khóa | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `pti.triage.provider` | `jev` (dev, staging); `fake` (CI, compose mặc định, demo offline) | Adapter (§4.3) |
| `pti.triage.jev.base-url` | mặc định của SDK | Ghi đè endpoint (Toxiproxy trong EXP-08) |
| `pti.triage.jev.sdk-retries` | `0` | Retry nội bộ của SDK |
| `pti.triage.jev.http-timeout` | `3s` | Timeout HTTP của SDK (lớn hơn TimeLimiter 2 s để TimeLimiter luôn cắt trước) |
| `pti.triage.poll-interval` | `2s` | Nghỉ giữa hai lần claim khi hàng đợi rỗng |
| `pti.triage.dlq.claim-batch-size` | `50` | DR-37 |
| `pti.triage.dlq.lease` | `2m` | DR-37 |
| `pti.triage.dlq.max-attempts` | `5` | DOC-15 |
| `pti.triage.dlq.retry-backoff` | `30s` | Backoff gốc; trần `pti.triage.dlq.retry-backoff-max` = `15m` |
| `pti.triage.dlq.parallelism` | `8` | §5.1 |
| `pti.triage.dlq.payload-excerpt-bytes` | `4096` | §6.1 |
| `pti.triage.auto-replay.min-confidence` | `0.9` | Luật 3–4 (so sánh `>`) |
| `pti.triage.auto-replay.confirm-min-confidence` | `0.5` | Luật 2 (dưới là `MANUAL`) |
| `pti.triage.auto-replay.max-per-record` | `2` | Luật 3; không được lớn hơn 2 (DB `CHECK`); validator từ chối giá trị > 2 |
| `pti.triage.auto-replay.categories` | `transient_network,upstream_api_error` | Category được auto (SDD §9.6) |
| `pti.triage.auto-replay.source-up-for` | `60s` | DR-38 |
| `pti.triage.auto-replay.scheduler-interval` | `5s` | §6.7 |
| `pti.triage.auto-replay.max-wait` | `6h` | Quá hạn → `PENDING_CONFIRM` |
| `pti.triage.auto-replay.max-per-minute` | `60` | Chặn đợt replay dồn dập |
| `pti.triage.health.etl-url` | `http://etl-stream:9080` | Cổng management của etl-stream (compose, k3d Service) |
| `pti.triage.health.timeout` | `1s` | |
| `pti.triage.health.interval` | `5s` | |
| `pti.triage.enrichment.lease` | `2m` | |
| `pti.triage.enrichment.max-attempts` | `5` | |
| `pti.triage.enrichment.claim-batch-size` | `20` | |
| `pti.triage.ticketing.parallelism`, `disruption.parallelism`, `dispatch.parallelism` | `2`, `2`, `4` | §5.1 |
| `pti.triage.ticketing.max-age` | `24h` | §5.5 |
| `pti.triage.ticketing.severity-min-confidence` | `0.6` | §7 |
| `pti.triage.ticketing.normal-min-confidence` | `0.8` | §7 |
| `pti.triage.disruption.data-issue-threshold` | `0.7` | FR-09.5 (so sánh `>`) |
| `pti.triage.disruption.max-age` | `30m` | §5.5 |
| `pti.triage.dispatch.max-age` | `5m` | §5.5 |
| `pti.triage.dispatch.demand-window` | `60m` | §9.1 |
| `pti.triage.quota-pause` | `15m` | §11.2 |
| `pti.triage.guard.pii-extra-keys` | rỗng | §12 |
| `resilience4j.*.instances.decision-model.*` | §11.1 | |
| `TYPESAFE_API_KEY` (env) | — | Bắt buộc khi `provider=jev`; Secret `pti-jev` trên k3d |
| `pti.triage.eval.command` | — | Chỉ profile `eval` (§14.1): `export-dlq` \| `export-ticketing` \| `export-disruption` \| `decide` |
| `pti.triage.eval.input` / `eval.output` | — | Đường dẫn file JSON Lines (§14.1) |
| `pti.triage.eval.from-id` / `eval.from-time` / `eval.limit` | `0` / — / `1000` | Phạm vi export (§14.1) |
| `pti.triage.eval.parallelism` | `1` | Số lời gọi đồng thời của `decide` |

Validator kiểm `confirm-min-confidence < min-confidence`, cả hai trong `(0, 1)`, và mọi `categories` thuộc enum. Sai → app không khởi động.

## 18. Metrics, log, trace

| Metric | Loại | Label | Ghi chú |
| --- | --- | --- | --- |
| `pti_triage_calls_total` | counter | `use_case`, `outcome` (`ok`, `timeout`, `error`, `invalid`, `rejected`, `quota`) | Một lần mỗi lời gọi (hoặc lần bị từ chối trước khi gọi) |
| `pti_triage_call_seconds` | histogram | `use_case` | Chỉ lời gọi `ok`; bucket 0,1…2,5 s |
| `pti_triage_decisions_total` | counter | `use_case`, `source` (`none` với insight), `category` (category, `likely_cause` hoặc `action`), `severity` (`0`,`1`,`2`, `none`) | Mỗi kết quả được ghi thành công. Nguồn của alert #27, #28 |
| `pti_triage_auto_replay_total` | counter | `decision` (`scheduled`, `pending_confirm`, `manual`, `requested`), `reason` (mã §6.3, §6.7, chữ thường) | |
| `pti_triage_backlog` | gauge | `use_case` | `count(*)` hàng đợi (`NEW` chưa tới hạn attempts / `PENDING`), cập nhật 15 s |
| `pti_triage_lease_lost_total` | counter | `use_case` | §5.3 |
| `pti_triage_lease_expired_total` | counter | `use_case` | Từ `LeaseSweeper` |
| `pti_triage_paused` | gauge | `reason` (`circuit_open`, `quota`, `fatal`, `provider_disabled`) | 0/1 |
| `pti_triage_source_up` | gauge | `source` | §6.6 |
| `resilience4j_*{name="decision-model"}` | | | Từ `resilience4j-micrometer` |
| `pti_dlq_rule_category_total` (**etl**) | counter | `source`, `category` | §10; DOC-22 §9 |

- Log: mỗi kết quả một dòng `INFO` `Triage decided` với MDC `use_case`, `item_id`, `model_version`, `outcome`, `category`, `confidence`, `decision`, `latency_ms`. Lỗi: `WARN` (transient), `ERROR` (fatal). Không log API key, không log state ở `INFO`.
- Trace: span `pti.triage.call` (DOC-28 §5) bao lời gọi, con là span HTTP client của SDK nếu SDK dùng `HttpClient` có instrumentation; không thì chỉ có span `pti.triage.call`. Span `pti.triage.apply` bao transaction ghi. Sampling 100% (DOC-28 §5).
- Dashboard `pti-triage` (DOC-28 §7): lời gọi theo `outcome`, p50/p95 `call_seconds`, trạng thái circuit, bulkhead còn trống, backlog từng use case, `decisions_total` theo category/severity, `auto_replay_total` theo `decision`/`reason`, `source_up`.

## 19. Lỗi

| Tình huống | Hệ quả | Ai thấy |
| --- | --- | --- |
| Jev timeout / 5xx rải rác | Record thử lại có backoff; sau 5 lần → `MANUAL` / `FAILED` | `pti_triage_calls_total{outcome}`, Ops console "Not yet classified" |
| Jev không truy cập được kéo dài | Circuit `OPEN`, vòng tạm dừng; ETL, analytics, API không đổi (FR-09.7) | Alert #8 `CircuitBreakerOpen` (`name="decision-model"`) |
| Hết quota | Tạm dừng 15 phút mỗi lần | `pti_triage_paused{reason="quota"}`, RB-04 |
| API key sai / hết hạn | Dừng hẳn, readiness `DOWN` | Alert #13 `FatalErrors`, RB-04 |
| Postgres lỗi | `TransientInfraException` từ JDBC: vòng nghỉ `poll-interval` × 2 rồi thử lại; việc đang giữ hết lease và được sweeper trả lại | Alert DB chung |
| etl-stream health không trả lời | Mọi nguồn coi như không UP; auto-replay chờ; sau 6 giờ → `PENDING_CONFIRM` | `pti_triage_source_up = 0` |
| Kafka `pti.events.ui` lỗi | Kết quả đã commit; sự kiện mất; log `WARN` (DR-42) | UI cập nhật qua polling |
| Backlog tăng | KEDA thêm replica (k3d); alert #30 `TriageBacklogHigh` | RB-04 |

Alert #30 `TriageBacklogHigh`: `max by (use_case) (pti_triage_backlog) > 500`, `for: 15m`, warning, RB-04.

## 20. Test bắt buộc

Unit test dùng lớp thuần với `Clock` cố định. Integration test dùng Testcontainers Postgres (migration thật, user `triage_writer`), Kafka Testcontainers cho sự kiện, và `FakeDecisionModel` hoặc `ScriptedDecisionModel` (test double trả kết quả/exception theo kịch bản). `WireMock` giả Jev cho các test adapter.

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| TG-01 | `DlqDecisionTable`: một ca cho mỗi luật 1, 2, 3, 4a, 4b, 4c, 5a, 5b, cộng biên `p = 0,5`, `p = 0,9`, `p = 0,9001` | Trạng thái và `reason` đúng bảng §6.3 (FR-09.2) |
| TG-02 | `AutoReplayGuard`: DQ-07 muộn; DQ-07 tương lai; DQ-07 thiếu `eventTimestamp`; DQ-12 có gốc; DQ-12 chưa có gốc; DQ-03 | Cho phép, từ chối, từ chối, cho phép, từ chối, từ chối |
| TG-03 | 100 dòng `NEW` đủ loại stage, `provider=fake` | Trong ≤ 60 s mọi dòng có category, severity, confidence, `model_version = fake@2026.09`; mỗi dòng có đúng hai action log (FR-09.1) |
| TG-04 | Hai worker cùng chạy trên 200 dòng | Mỗi dòng được gọi mô hình đúng một lần (đếm trên `ScriptedDecisionModel`) |
| TG-05 | `ScriptedDecisionModel` ném `Unavailable(TIMEOUT)` 5 lần liền cho một dòng | `triage_attempts` 1…5, lease tăng 30 s, 60 s, 120 s, 240 s; cuối cùng `MANUAL`, action log `TRIAGE_FAILED` × 5 và `MANUAL_REQUIRED` |
| TG-06 | Ném `Unavailable(BULKHEAD_FULL)` và `CIRCUIT_OPEN` | `triage_attempts` không đổi; lease `now + 30 s`; không có action log |
| TG-07 | Circuit mở (10 lỗi liên tiếp trên WireMock 500) | Vòng dừng claim; `pti_triage_paused{reason="circuit_open"} = 1`; sau 30 s half-open, 3 lời gọi thử thành công → claim tiếp |
| TG-08 | WireMock trả 401 | Mọi vòng dừng; readiness `DOWN`; dòng đang giữ về `NEW`, lease `NULL`, attempts không đổi |
| TG-09 | Kill worker giữa claim và apply (dừng container), rồi chờ sweeper | Dòng về `NEW` với attempts + 1, `TRIAGE_FAILED` `lease_expired` |
| TG-10 | `ScriptedDecisionModel` chờ 3 s; trong lúc đó test đặt `triage_lease_until` về quá khứ và chạy `LeaseSweeper` (dòng về `NEW`) | 0 dòng, kết quả bị bỏ, `pti_triage_lease_lost_total` + 1, không action log |
| TG-11 | Cờ `triage.dlq.enabled = false`, thêm 10 dòng | Sau 30 s cả 10 vẫn `NEW`, không lời gọi nào (FR-09.9) |
| TG-12 | Cờ `triage.disruption.enabled = false`, có 3 disruption `PENDING` | Cả 3 `SKIPPED`; không lời gọi |
| TG-13 | `AutoReplayScheduler`: dòng `AUTO_REPLAY_SCHEDULED`, health stub `source-gtfs-rt` UP từ 30 s, rồi 65 s | Lần đầu không làm gì; lần sau `REPLAY_REQUESTED`, `auto_replay_count = 1`, một `replay_request` `requested_by = 'auto'`, `idempotency_key = 'auto:<id>:1'`, action log actor `auto` có confidence (FR-09.2) |
| TG-14 | Như TG-13 nhưng health stub `DOWN` ở giây 40, `UP` lại | Đồng hồ 60 s đếm lại từ lúc `UP` lại |
| TG-15 | Health endpoint timeout | Không auto-replay; `pti_triage_source_up = 0` |
| TG-16 | Tắt cờ `triage.auto-replay.enabled` khi có 2 dòng `AUTO_REPLAY_SCHEDULED` | Cả hai → `PENDING_CONFIRM`, `reason = auto_replay_disabled` |
| TG-17 | Dòng `AUTO_REPLAY_SCHEDULED` có `triaged_at` 7 giờ trước | → `PENDING_CONFIRM`, `reason = source_not_recovered` |
| TG-18 | Dòng đã auto 2 lần, replay lỗi lại, triage lại ra `transient_network` 0,95 | `MANUAL`, `reason = auto_replay_limit`; không `replay_request` mới |
| TG-19 | Test quyền: `UPDATE ops.dead_letter SET auto_replay_count = 3` với `triage_writer` | Lỗi `CHECK` (lớp chặn cuối) |
| TG-20 | 150 dòng đủ điều kiện, nguồn UP | Phút đầu đúng 60 `replay_request`; phần còn lại ở các phút sau |
| TG-21 | Ticketing: anomaly `REFUND_RATIO` (Fake) | `category = fraud_suspect`, `severity = 2`; alert `ticketing:<id>` có `body.category`, `severity = 2`; một `alert.updated` (FR-09.4) |
| TG-22 | Ticketing: Fake bị thay bằng Scripted trả `normal` 0,85 | Alert `severity = 0` |
| TG-23 | Ticketing: analytics đổi `summary` giữa claim và apply | Apply 0 dòng; dòng ở `PENDING` với summary mới; lần sau được phân loại lại |
| TG-24 | Disruption khi tỷ lệ DLQ GTFS-rt 15 phút = 2% (Fake) | `data_issue_probability = 0,85`, audience alert = `ENGINEERING`; sự kiện `alert.retracted` (audience `PUBLIC`) rồi `alert.updated` (`ENGINEERING`) (FR-09.5) |
| TG-25 | Disruption bình thường | Audience giữ `PUBLIC`; `body.likelyCause` có; một `alert.updated` |
| TG-26 | Analytics đóng episode rồi nâng severity sau khi audience đã là `ENGINEERING` | Audience vẫn `ENGINEERING` |
| TG-27 | Dispatch: bunching `gapRatio = 0,16`, demand `HIGH` | Một suggestion `hold_follower` 0,82, `id = InsightIds.dispatchSuggestion(bunchingId)`, `state_snapshot` bằng state đã gửi; bunching `DONE`; một `dispatch.suggested` (FR-09.6) |
| TG-28 | Dispatch: episode `CLOSED` trước khi claim | `SKIPPED`, không suggestion |
| TG-29 | Dispatch: tính lại analytics tạo lại episode cùng id đã có suggestion và feedback | Suggestion và `operator_feedback` giữ nguyên |
| TG-30 | `PiiGuard.assertClean` với state có khóa `customer_ref`, `customerRef`, `Customer-Ref`, lồng 3 cấp | Cả ba ném `PiiViolationException`; state của cả bốn state builder trên fixture đầy đủ thì sạch (FR-09.10) |
| TG-31 | `PiiGuard.scrub` với email, số thẻ `4111 1111 1111 1111`, số 12 chữ số | Hai giá trị đầu bị thay; số 12 chữ số giữ nguyên |
| TG-32 | `JevDecisionModel` với WireMock trả fixture S-01 | `Decision` đúng label, confidence, mức severity theo `round(Σ i·p_i)`; `model_version` theo §13 |
| TG-33 | WireMock trả label lạ, xác suất 1,2, thiếu khóa | `InvalidDecisionException` cả ba |
| TG-34 | Bảng ánh xạ lỗi §11.2 (WireMock 400, 422, 429, 500, 503, delay 3 s, connection reset) | Đúng exception và `reason` |
| TG-35 | Toxiproxy làm Jev timeout trong 5 phút trong khi ETL chạy tải nền | Throughput ETL không đổi (± 5%); không lỗi ở etl và api; UI hiện "Unclassified" (FR-09.7). Tag `fault` |
| TG-36 | Alert rule: `promtool test rules` cho #27–30 với series giả | Bắn đúng ngưỡng; #29 bắn khi `pti_dlq_rule_category_total{category="upstream_api_error"}` tăng 51 trong 1 giờ dù không có metric triage (FR-09.8) |
| TG-37 | `DlqRuleClassifier` trên mọi stage/rule | Khớp cột "Category" §15.1 |
| TG-38 | Validator cấu hình: `max-per-record = 3`; `confirm-min-confidence = 0.95` | App không khởi động, thông báo nêu khóa sai |
| TG-39 | Profile `eval`: `export-dlq` trên 10 dòng DLQ mẫu rồi `decide` với `provider=fake` | File đầu ra có 10 dòng, `decision` khớp §15.1; DB không đổi (checksum bảng `ops.dead_letter` trước và sau bằng nhau); không bean vòng xử lý nào được tạo |

E2E (Playwright, compose, `PTI_TRIAGE_PROVIDER=fake`, chạy từ P6):

| ID | Kịch bản | Kỳ vọng |
| --- | --- | --- |
| E2E-TRIAGE-01 | `operator`: `POST /sim/scenarios/late-delivery` (`ratio` 0.01, `delay` `PT6M`, `duration` `PT1M`) trên compose có `PTI_DQ_MAX_CLOCK_SKEW=5m` (`make up-demo`); mở `/ops/dlq` lọc `status=AUTO_REPLAY_SCHEDULED` | Record xuất hiện với "Auto-replay scheduled"; sau ≤ 90 s chuyển sang "Replayed"; lịch sử có "Replay requested" với actor "auto" |
| E2E-TRIAGE-02 | Alert feed AC-6: `operator` kích hoạt `bad-data` (`ratio` 0.05, `kinds` `["out_of_bbox"]`) rồi `disruption` cho tuyến 18; hai context anonymous và `viewer` | Anonymous: dòng gián đoạn biến mất trong ≤ 10 s; viewer: dòng còn, audience "Engineering" |
| E2E-TRIAGE-03 | `ticket-spike` → mở `/ops/ticketing` | Anomaly có "AI classification" khác "Not yet classified" (Ops console ticketing AC-2) |

E2E-DLQ-04 (Confirm queue) và phần "Accept" của E2E-MAP-02, E2E-ALERT-02 cũng bắt đầu chạy từ P6.

## 21. Thay đổi kéo theo ở tài liệu khác

| Tài liệu | Thay đổi |
| --- | --- |
| DOC-15 §4.3 | Cạnh `AUTO_REPLAY_SCHEDULED → PENDING_CONFIRM` (triage-worker, `CONFIRM_REQUESTED`); ghi chú `TRIAGED` chỉ tồn tại trong transaction; `TRIAGING → MANUAL` gộp khi hết lần thử |
| DOC-17 | `triage_writer`: bỏ `INSERT` trên `ops.alert_event` (chỉ `SELECT`), thêm `UPDATE (severity)` |
| DOC-20 §6.1, DOC-29 | Health group `sources` có `show-components=always` |
| DOC-22 §9 | Metric `pti_dlq_rule_category_total` |
| DOC-25 §7.9 | Kịch bản `late-delivery` |
| DOC-28 | §3.6 metric; alert #27–30; bỏ ý "thêm vế severity 2 vào `DlqRateHigh`" (ghi chú 3) |
| DOC-32 E-80 | `DlqSevereRecords` → `DLQ_SEVERE`; ví dụ `modelVersion` và `stateSnapshot` |
| DOC-33 | Ví dụ `modelVersion` |
| DOC-39 | Biến `PTI_DQ_MAX_CLOCK_SKEW` cho etl-stream (`make up-demo` đặt `5m`) |
| DOC-07, UC-13 | triage-worker không ghi `alert_event` |
| DOC-44 §13 | Tiền tố `TG`, `E2E-TRIAGE` |
| DOC-29 §3.4 | Khóa `pti.triage.eval.*` |
| DR register | DR-72 (vòng xử lý, luật chặn cuối), DR-73 (demo auto-replay, `model_version`), DR-74 (KEDA, virtual thread) |

## 22. Câu hỏi còn mở

Không có.
