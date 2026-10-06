# Exercise: `ticket-reservation-payment`

> Prerequisite reading: `locks-concurrent-guide.md` (explanations and code for every tool used below).

You will build a small but **production-shaped** ticket reservation and payment service, and prove with **concurrent tests** that it is correct. Each challenge targets one tool from `locks-guide.md`.

**How to work:**
1. Do the challenges **in order** (each builds on the previous).
2. For each one, write the **validation test first** (red), then make it green.
3. Open a hint only after 15 minutes of real effort. Hints are progressive (Hint 1 = nudge, Hint 3 = near-solution).
4. Tick the checklist at the end. Do not move on while a validation rule is red.

## 0. Domain and setup

### Business rules
- An **Event** has seats. A **Seat** is `FREE`, `HELD` (temporarily reserved while paying) or `SOLD`.
- A user **reserves** a seat (hold), then **pays**. Payment debits the user's **Wallet** and calls an external **Payment Gateway**.
- A **webhook** from the gateway confirms the payment (can arrive **twice**, on different pods).
- Commission fees depend on payment method and are read on every payment.
- A page shows **remaining tickets** and is hit very often.

### Tech
- Java 21+, Spring Boot 4.0, Spring Data JPA, PostgreSQL, Redis
- Tests: JUnit 5, Testcontainers (PostgreSQL + Redis), Awaitility
- Dependencies (names may vary slightly in Boot 4; check the starters page):
  `spring-boot-starter-webmvc` (or `-web`), `spring-boot-starter-data-jpa`,
  `spring-boot-starter-data-redis`, `spring-integration-redis`, `spring-boot-starter-actuator`,
  `spring-boot-testcontainers`, `testcontainers` (postgresql), `awaitility`,
  optionally `resilience4j-spring-boot3` / Spring Boot 4 compatible Resilience4j starter.

### Suggested package layout
```
com.ticketnow
 ├─ event      Seat, SeatRepository, ReservationService, ReservationFacade, ReservationController
 ├─ wallet     Wallet, WalletRepository, WalletService
 ├─ payment    PaymentService, GatewayClient, GatewayTokenProvider, PaymentWebhookService, FeeRules
 ├─ stock      StockView
 └─ config     ResilienceConfig, LockConfig
```

### Data model (start here)
```
seat(id, event_id, label, status, reserved_by, held_until, version)
wallet(id, user_id, balance_cents, version)
orders(id, seat_id, user_id, amount_cents, status, idempotency_key UNIQUE, version)
payment_audit(id, order_id, type, created_at)     -- one row per confirmation
```

### Test helper you will reuse in every challenge
Write this once. It fires N tasks **at the same instant** (the key to reproducing races).

```java
public final class Race {
    /** Runs `task` N times simultaneously and returns the exceptions/results of each. */
    public static <T> List<Result<T>> run(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);   // or virtual threads
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Result<T>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();                         // everyone waits for the starting gun
                try { return Result.ok(task.call()); }
                catch (Throwable t) { return Result.fail(t); }
            }));
        }
        ready.await();
        go.countDown();                             // fire!
        List<Result<T>> out = new ArrayList<>();
        for (var f : futures) out.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }
    public record Result<T>(T value, Throwable error) {
        static <T> Result<T> ok(T v) { return new Result<>(v, null); }
        static <T> Result<T> fail(Throwable t) { return new Result<>(null, t); }
        public boolean success() { return error == null; }
    }
}
```

> **Why this matters:** a concurrency bug that "never happens on your laptop" will happen in production. If your test cannot reproduce the bug **before** the fix, it proves nothing **after** the fix.

---

## Challenge 1: Reproduce the bug (the naive version)

**Goal:** Build the naive `reserve()` with **no protection** and watch it break.

**Tasks**
1. Create `Seat` (no `@Version`) and `ReservationService.reserve(seatId, userId)` doing read, check `FREE`, set `HELD`, save.
2. Write `DoubleBookingTest`: 1 seat, 50 users reserve it simultaneously with `Race.run(50, ...)`.

**Validation rules**
- [ ] V1.1 The test **fails** on the naive code: more than 1 call succeeds (that's the proof the bug is real). Run it 10 times; it should fail at least 8.
- [ ] V1.2 You can explain in 3 sentences, in your own words, **why** both threads read `FREE`.

<details><summary>Hint 1</summary>
Use `@Transactional` on the service and Postgres in Testcontainers (not H2: H2's locking behaves differently).
</details>
<details><summary>Hint 2</summary>
If it passes by luck, increase the thread count or add a tiny `Thread.sleep(5)` between the check and the save (only for this reproduction).
</details>
<details><summary>Hint 3</summary>
Count successes with <code>results.stream().filter(Race.Result::success).count()</code> and assert <code>== 1</code>. It will fail with numbers like 7 or 50.
</details>

---

## Challenge 2: Optimistic locking with `@Version`

**Goal:** Exactly one winner, everyone else gets a clean **409 Conflict**.

**Tasks**
1. Add `@Version Long version` to `Seat`.
2. Map `ObjectOptimisticLockingFailureException` and `SeatAlreadyTakenException` to **HTTP 409** in a `@RestControllerAdvice`.
3. Expose `POST /seats/{id}/reserve` with body `{ "userId": "..." }`.

**Validation rules**
- [ ] V2.1 `DoubleBookingTest` (50 users, 1 seat) now yields **exactly 1 success**, 49 failures.
- [ ] V2.2 Every failure maps to **409** (never 500). Test through `MockMvc`/`TestRestTemplate`.
- [ ] V2.3 The DB has exactly 1 `HELD` row and `reserved_by` equals the winner's id.
- [ ] V2.4 Log output shows `UPDATE seat ... WHERE id=? AND version=?` (enable `spring.jpa.show-sql` temporarily).

<details><summary>Hint 1</summary>
The exception can be thrown at <b>commit</b>, not inside your method. Which layer sees it?
</details>
<details><summary>Hint 2</summary>
The exception is thrown when the transaction proxy commits, so it surfaces <b>outside</b> your <code>@Transactional</code> method. Catch it in the advice (or in a non-transactional facade).
</details>
<details><summary>Hint 3</summary>
<code>@ExceptionHandler({ObjectOptimisticLockingFailureException.class, SeatAlreadyTakenException.class})</code> returning <code>ProblemDetail.forStatus(409)</code>.
</details>

---

## Challenge 3: Retry done right

**Goal:** 50 users, **10 seats**: all 10 seats get sold, nobody is wrongly rejected because of a version clash on a *different* seat flow.

**Tasks**
1. Add `@EnableResilientMethods` and a `ReservationFacade` with `@Retryable` on optimistic failures (3 retries, short delay, jitter if available).
2. Users now say "any seat of event X": the service picks the first FREE seat. Many users will collide on the *same first seat*.
3. The retry must re-read data (new transaction each attempt).

**Validation rules**
- [ ] V3.1 50 users on 10 seats: **exactly 10 succeed, 40 get "sold out" (409)**. No seat sold twice. No seat left FREE while a user was rejected as "sold out" (**no false negatives**).
- [ ] V3.2 Retries never run **inside** the same transaction (verify with a log of transaction ids, or a test showing a retry sees fresh data).
- [ ] V3.3 Max retries is bounded: a pathological test (forced conflicts) ends with a clear error, not an infinite loop.

<details><summary>Hint 1</summary>
Why does <code>@Retryable</code> on the same method that has <code>@Transactional</code> not work as expected? Think of proxy ordering and transaction scope.
</details>
<details><summary>Hint 2</summary>
Put <code>@Retryable</code> on the <b>outer</b> bean (facade) and <code>@Transactional</code> on the <b>inner</b> bean (service). The retry then opens a brand-new transaction each time.
</details>
<details><summary>Hint 3</summary>
Pick a seat with a query like <code>findFirstByEventIdAndStatus(eventId, FREE)</code>. After a conflict the next attempt will find the <i>next</i> free seat, which is exactly why 10 seats can all sell.
</details>

---

## Challenge 4: Pessimistic lock for the wallet

**Goal:** A hot wallet receives many simultaneous debits; the balance never goes negative and no money is lost or created.

**Tasks**
1. `WalletService.debit(walletId, cents)` using `PESSIMISTIC_WRITE` and a **3 s lock timeout**.
2. `WalletService.transfer(fromId, toId, cents)` (e.g. refund between wallets).
3. Return **402** for insufficient funds, **503/409** for lock timeout.

**Validation rules**
- [ ] V4.1 Wallet with 10,000 cents. 200 concurrent debits of 100 cents: **exactly 100 succeed**, balance ends at **0**, never negative (assert on every observed state, not just the end).
- [ ] V4.2 Conservation of money: for 100 random concurrent transfers between 5 wallets, `sum(balances)` before == after.
- [ ] V4.3 **Deadlock test:** run `transfer(A,B)` and `transfer(B,A)` simultaneously 200 times. **Zero** deadlock errors.
- [ ] V4.4 Lock timeout works: hold a lock in one thread for 5 s; a second `debit` fails in about 3 s with the mapped error (not 30 s, not forever).
- [ ] V4.5 No remote/HTTP call happens inside the transaction (code review rule).

<details><summary>Hint 1</summary>
V4.3: what order do two opposite transfers lock the rows in? Is that order <i>consistent</i>?
</details>
<details><summary>Hint 2</summary>
Always lock wallets in a <b>deterministic order</b> (e.g. ascending id), regardless of "from/to" direction. Then apply debit and credit.
</details>
<details><summary>Hint 3</summary>
<pre>Long first = Math.min(fromId, toId), second = Math.max(fromId, toId);
Wallet w1 = repo.findByIdForUpdate(first).orElseThrow();
Wallet w2 = repo.findByIdForUpdate(second).orElseThrow();
Wallet from = w1.getId().equals(fromId) ? w1 : w2;</pre>
For V4.4 the exception is <code>PessimisticLockingFailureException</code> / <code>CannotAcquireLockException</code> depending on the DB driver.
</details>

---

## Challenge 5: `ReentrantLock` for the gateway token

**Goal:** When the gateway token expires, **only one** thread refreshes it, even under 500 simultaneous payments.

**Tasks**
1. Implement `GatewayTokenProvider` (see `locks-guide.md`, section 3) with a **fake** auth server that counts calls and sleeps 300 ms.
2. Add a method `refreshAll()` that takes the lock and calls `getToken()` (to prove **reentrancy**).
3. Handle `InterruptedException` correctly.

**Validation rules**
- [ ] V5.1 500 threads call `getToken()` with an expired token: the fake auth server is called **exactly once**.
- [ ] V5.2 If the auth server hangs for 10 s, callers fail with a timeout in about 2 s (`tryLock` timeout), not 10 s+.
- [ ] V5.3 `refreshAll()` completes without deadlock (reentrancy), and `lock.getHoldCount()` is 0 afterwards.
- [ ] V5.4 If `callAuthServer()` throws, the lock is still released (a following call can acquire it).
- [ ] V5.5 After catching `InterruptedException` you restore the flag (`Thread.currentThread().interrupt()`); a test interrupts a waiting thread and asserts `isInterrupted()`.

<details><summary>Hint 1</summary>
Why do you need the expiry check <b>twice</b> (before and inside the lock)?
</details>
<details><summary>Hint 2</summary>
This is "double-checked locking": fast path without lock, then lock, then re-check. The fields must be <code>volatile</code>.
</details>
<details><summary>Hint 3</summary>
For V5.4 the <code>unlock()</code> goes in <code>finally</code>, and <code>tryLock()</code> itself must be <b>outside</b> the try block (otherwise you unlock a lock you never acquired).
</details>

---

## Challenge 6: `ReentrantReadWriteLock` for fee rules

**Goal:** Thousands of payments read the fee rules while a background reload updates them, with no stale-mixed data and no long pauses.

**Tasks**
1. `FeeRules.rateFor(method)` under the read lock; `reload()` under the write lock.
2. `reload()` loads from the DB **outside** the lock (simulate 500 ms) and swaps inside.
3. **Then** reimplement a second version `FeeRulesImmutable` using a `volatile` reference to an immutable `Map.copyOf(...)`. Compare.

**Validation rules**
- [ ] V6.1 **Consistency:** rules hold a pair of values that must change together (e.g. `cardRate` and `cardMinFee`, versions "v1" then "v2"). 20 reader threads reading in a loop **never** observe a mix of v1 and v2.
- [ ] V6.2 **Latency:** while `reload()` runs (simulated 500 ms load), readers' p99 latency stays **under 5 ms** (proves slow work is outside the lock).
- [ ] V6.3 Show that read-to-write **upgrade deadlocks**: write a test (with a timeout) that tries `readLock().lock()` then `writeLock().lock()` on the same thread and detects the hang. Then document the fix in a comment.
- [ ] V6.4 Written answer (3 lines): when is `FeeRulesImmutable` better than the lock version?

<details><summary>Hint 1</summary>
V6.1: the reader must grab <b>both</b> values under <b>one</b> read-lock acquisition, not two separate calls.
</details>
<details><summary>Hint 2</summary>
Return a single immutable <code>record FeeSchedule(BigDecimal cardRate, BigDecimal cardMinFee)</code> from one method.
</details>
<details><summary>Hint 3</summary>
V6.3: use <code>assertTimeoutPreemptively(Duration.ofSeconds(2), ...)</code> and run the code in a separate thread, since the deadlocked thread cannot be interrupted by a plain lock call.
</details>

---

## Challenge 7: `StampedLock` for the remaining-ticket counter

**Goal:** A read path that scales to a very high read rate and **never returns a torn value**.

**Tasks**
1. `StockView` stores **two** fields that must stay consistent: `available` and `sold`, with the invariant `available + sold == total`.
2. Implement `read()` with `tryOptimisticRead` and `validate`, with a read-lock fallback.
3. Implement `update(...)` under the write lock.
4. Benchmark against `ReentrantReadWriteLock` (simple loop benchmark, or JMH if you like).

**Validation rules**
- [ ] V7.1 **Invariant:** 16 reader threads call `read()` millions of times while 1 writer updates continuously; **every** snapshot satisfies `available + sold == total` (count violations: must be **0**).
- [ ] V7.2 **Prove validation is necessary:** remove the `validate()` call in a copy of the class; the invariant test now fails (at least once in several runs). Keep this as a "negative test".
- [ ] V7.3 Your code never calls a StampedLock method while already holding it (it's not reentrant). Add a test that shows a reentrant attempt blocks.
- [ ] V7.4 Benchmark note (in a README line): throughput of Stamped vs ReadWrite under 95% reads. Do you see a difference on your machine?

<details><summary>Hint 1</summary>
In the optimistic path, copy <b>all</b> fields into local variables <b>before</b> calling <code>validate</code>, then use only the locals.
</details>
<details><summary>Hint 2</summary>
Return an immutable <code>record Snapshot(int available, int sold)</code> built from the locals. Never compute from the fields after validation.
</details>
<details><summary>Hint 3</summary>
For V7.2, a torn read can happen between reading <code>available</code> and <code>sold</code>. To make it likely, have the writer do <code>available = x; Thread.yield(); sold = total - x;</code> inside the write lock.
</details>

---

## Challenge 8: Virtual threads + `@ConcurrencyLimit` + Bulkhead

**Goal:** Handle thousands of simultaneous payment requests **without overloading the gateway**.

**Tasks**
1. Enable `spring.threads.virtual.enabled=true`.
2. Write a `FakeGateway` (a Spring bean for tests) that sleeps 200 ms per call and **records the peak number of concurrent calls** (`AtomicInteger current`, `LongAccumulator peak`). If concurrency exceeds 50, it returns an error.
3. Protect `GatewayClient.charge()` with `@ConcurrencyLimit(40)`.
4. Then switch to a **Bulkhead** (max wait 200 ms) with a fallback that returns **503 + Retry-After**.

**Validation rules**
- [ ] V8.1 With `@ConcurrencyLimit(40)`: 2,000 simultaneous `charge()` calls, `FakeGateway.peak <= 40` and **0** gateway errors; all calls eventually succeed.
- [ ] V8.2 Test runs on virtual threads (`Thread.currentThread().isVirtual()` is true inside a request).
- [ ] V8.3 With the Bulkhead: callers waiting more than 200 ms get the fallback; the **fastest failure** in the test is about 200 ms (not 30 s), and `peak <= 40` still holds.
- [ ] V8.4 Per-pod math: write down in the README the limit per pod for **5 pods** and a gateway cap of 50 (answer: 10). Add a config property `gateway.max-concurrent-per-pod`.
- [ ] V8.5 **Pinning check:** run with `-Djdk.tracePinnedThreads=full` and confirm no pinned-thread stack traces from **your** code in the payment path. If you find one (a `synchronized` around blocking I/O), replace it with a `ReentrantLock`.
- [ ] V8.6 Metrics: expose in Actuator/Micrometer the number of rejected calls and the current in-flight count.

<details><summary>Hint 1</summary>
The fake gateway must increment <code>current</code> on entry, update <code>peak</code> with <code>peak.accumulate(current)</code>, and decrement in <code>finally</code>.
</details>
<details><summary>Hint 2</summary>
For 2,000 simultaneous calls use <code>Executors.newVirtualThreadPerTaskExecutor()</code> in the test, not a 2,000-thread platform pool.
</details>
<details><summary>Hint 3</summary>
If <code>@ConcurrencyLimit</code> seems ignored, check that it's called through the <b>Spring proxy</b> (from another bean), and that <code>@EnableResilientMethods</code> is present. Self-invocation bypasses the proxy.
</details>

---

## Challenge 9: `LockRegistry`: the duplicate webhook

**Goal:** The same webhook arrives twice at the same time on **two different application instances**; the order is confirmed **once**.

**Tasks**
1. Start Redis with Testcontainers.
2. Create `RedisLockRegistry` (TTL 30 s) and `PaymentWebhookService.onPaymentConfirmed(orderId, eventId)`.
3. **Simulate 2 pods:** start **two** Spring contexts (or two `RedisLockRegistry` instances with separate connections) in the same test.
4. Add a `payment_audit` insert **inside** the lock, plus a DB **unique constraint** on `(order_id, type)`.

**Validation rules**
- [ ] V9.1 Two webhooks for the same `orderId` fired simultaneously on pod A and pod B: order status is `PAID`, and `payment_audit` has **exactly 1** row for it.
- [ ] V9.2 Webhooks for **different** orders run **in parallel** (the lock is per order, not global). Test: 2 orders with a 500 ms handler finish in about 500 ms total, not 1000 ms.
- [ ] V9.3 **Safety net test:** disable the lock in a test profile, fire 2 webhooks. The DB **unique constraint** or `@Version` still prevents a double confirmation (the second fails cleanly). *This proves the lock is an optimization, not your only protection.*
- [ ] V9.4 **TTL scenario:** make the handler sleep longer than the TTL (use a 2 s TTL in the test). Show that a second pod can enter, and that your idempotency check (`isPaid()`) / constraint still keeps data correct.
- [ ] V9.5 If the pod dies while holding the lock (simulate by killing the first context without unlocking), the other pod can acquire it after the TTL.
- [ ] V9.6 The handler returns **HTTP 200** for duplicates (the bank must not retry forever) and logs "duplicate ignored".

<details><summary>Hint 1</summary>
Both contexts must use the <b>same Redis</b> and the <b>same registry key prefix</b>, otherwise they will never see each other's lock.
</details>
<details><summary>Hint 2</summary>
<code>lock.tryLock(3, SECONDS)</code> returning <code>false</code> means "someone else is processing". Decide: skip silently, or re-check the order state and return 200.
</details>
<details><summary>Hint 3</summary>
V9.4 reveals the key insight: <b>the lock expiring is normal</b>. Your business operation must be idempotent <i>independently</i> of the lock. Use a unique index on <code>payment_audit(order_id, type)</code> and catch <code>DataIntegrityViolationException</code>.
</details>

---

## Challenge 10: Final integration and load test

**Goal:** Prove the whole system holds under realistic load.

**Flow per user:** `reserve seat` then `pay (wallet debit + gateway charge)` then `webhook confirmation (sometimes duplicated)`.

**Scenario:** 1 event, **100 seats**, **1,000 users**, each with a wallet of 5,000 cents, price 2,000 cents. 3 simulated pods. 10% of webhooks are sent twice. 5% of gateway calls fail randomly.

**Tasks**
1. Add an **idempotency key** header on `POST /pay` (unique constraint on `orders.idempotency_key`).
2. If the gateway fails after the wallet debit, **compensate** (refund the wallet, release the seat).
3. Run the scenario (a test or a Gatling/k6 script).

**Validation rules (global invariants: all must hold at the end)**
- [ ] V10.1 **Exactly 100** seats `SOLD`, **0** seats sold twice, `0 <= HELD`.
- [ ] V10.2 **Money conservation:** `sum(wallets) + sum(successful charges) == initial total`. No money created or lost.
- [ ] V10.3 Every `SOLD` seat has **exactly one** `PAID` order and exactly one `payment_audit` row.
- [ ] V10.4 No wallet is ever negative.
- [ ] V10.5 Users who failed receive a **clean business status** (409 sold out, 402 funds, 503 retry later), **zero 500s**.
- [ ] V10.6 Replaying the same `Idempotency-Key` returns the **same response** and creates no second order or debit.
- [ ] V10.7 `FakeGateway.peak <= per-pod limit × pods`.
- [ ] V10.8 No thread leaks: after the test, thread count returns to baseline (check with `ThreadMXBean`).
- [ ] V10.9 A **held seat expires**: a seat `HELD` for more than 5 min (use 2 s in tests) becomes `FREE` again via a `@Scheduled` job protected so that it runs on **one** pod only (ShedLock, or `LockRegistry`).

<details><summary>Hint 1</summary>
V10.2: write a SQL query that sums balances and successful charges, and run it as the last assertion. If money disappears, look at the failure path between debit and gateway call.
</details>
<details><summary>Hint 2</summary>
Order of operations matters. A common safe design: create the order as `PENDING` (unique idempotency key), debit the wallet, call the gateway **outside** the DB transaction, then mark `PAID` or compensate. Never hold a DB lock during the HTTP call.
</details>
<details><summary>Hint 3</summary>
Consider the outbox/saga idea: store "compensation needed" rows so a crash between debit and refund can be repaired by a background job. For V10.9 use ShedLock's <code>@SchedulerLock</code> (or a <code>LockRegistry</code> key) so only one pod releases expired holds.
</details>

---

## Bonus challenges (senior level)

1. **Fencing token:** add a monotonically increasing token (Redis `INCR`) when a lock is acquired; the DB write refuses a token lower than the last one seen. Demonstrate that this fixes the TTL-expiry problem (Challenge 9, V9.4) rigorously.
2. **Hot seat contention:** compare `@Version` + retry vs `FOR UPDATE` for 500 users fighting for **one** seat. Measure throughput and p99 latency; write a 5-line conclusion.
3. **Observability:** Micrometer metrics for lock wait time, retry count, optimistic conflicts, bulkhead rejections; one Grafana-ready dashboard JSON.
4. **Chaos:** kill a pod mid-payment (stop a Spring context) and prove the invariants still hold after recovery.

---

## Final checklist

| Challenge | Tool | Done |
|---|---|---|
| 1 | Reproduce race condition | [ ] |
| 2 | `@Version` optimistic locking | [ ] |
| 3 | `@Retryable` with correct transaction boundaries | [ ] |
| 4 | Pessimistic `FOR UPDATE` + deadlock avoidance | [ ] |
| 5 | `ReentrantLock` (tryLock, reentrancy, interrupts) | [ ] |
| 6 | `ReentrantReadWriteLock` vs immutable swap | [ ] |
| 7 | `StampedLock` optimistic read | [ ] |
| 8 | Virtual threads + `@ConcurrencyLimit` + Bulkhead | [ ] |
| 9 | `LockRegistry` + idempotent safety net | [ ] |
| 10 | Full integration and load test | [ ] |

## Self-assessment: can you answer these without notes?

1. Why does a `ReentrantLock` not prevent double booking across 3 pods?
2. Why must `@Retryable` wrap the transactional bean, not live inside it?
3. How do you avoid deadlocks in a transfer between two wallets?
4. Why does a distributed lock need a TTL, and why is it still not enough on its own?
5. What are virtual threads **not** protecting you from?
6. When would you choose pessimistic over optimistic locking?
7. What invariant would you assert to prove a payment system never loses money?

If you can answer all seven and all validation rules are green, you handle concurrency at a solid **mid-to-senior** level. Good luck.
