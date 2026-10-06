# Locks in Production: Reservation & Payment Systems (Spring Boot 4)

> Companion exercise: `ticketnow-exercise.md`

> **Audience:** junior developer progressing to senior.
> **Stack:** Java 21+, Spring Boot 4.0 / Spring Framework 7, PostgreSQL, Redis.

---

## The running example: "TicketNow"

Seat **12A** has one ticket left. Two users, Awa and Moussa, click "Pay" in the same millisecond. Your service runs on **3 pods** behind a load balancer.

```
Awa  ──► Pod 1 ─┐
                ├──► DB: seat 12A (status = FREE)
Moussa ► Pod 2 ─┘

 Pod 1: SELECT seat → FREE ✔
 Pod 2: SELECT seat → FREE ✔      ← both read before either writes
 Pod 1: UPDATE seat → RESERVED (Awa)
 Pod 2: UPDATE seat → RESERVED (Moussa)   ← overwrites Awa!

 Result: two people paid for one seat.
```

This is a **race condition** (a "lost update"). Every tool below is a different way to prevent it. They differ in **where** they protect (one JVM or the whole system) and **how** (wait, or detect and retry).

**Two families of strategy:**
- **Pessimistic:** "I assume a conflict will happen, so I lock first, and others wait." A bathroom with a key.
- **Optimistic:** "I assume conflicts are rare. I work freely, and at the end I check nobody changed the data. If they did, I retry." Like editing a shared doc and getting "someone edited this, reload".

---

## 1. `@Version`: optimistic locking (default for reservations)

**Simple idea:** Each row carries a version number. You may only update it if the version is still the one you read.

```
 Awa read seat (v=5)      Moussa read seat (v=5)
 Awa:    UPDATE ... SET status='RESERVED', version=6 WHERE id=12A AND version=5  → 1 row ✔
 Moussa: UPDATE ... SET status='RESERVED', version=6 WHERE id=12A AND version=5  → 0 rows ❌
                                                     → exception → "seat just taken"
```

No lock is held in the database. The conflict is detected by the `WHERE version = ?` condition. It works across all pods because the database decides.

```java
@Entity
public class Seat {
    @Id private Long id;
    private String status;          // FREE / RESERVED
    private String reservedBy;

    @Version                        // Hibernate adds "AND version = ?" to every UPDATE
    private Long version;

    public void reserve(String userId) {
        if (!"FREE".equals(status)) throw new SeatAlreadyTakenException(id);
        this.status = "RESERVED";
        this.reservedBy = userId;
    }
}

@Service
public class ReservationService {
    private final SeatRepository seats;
    public ReservationService(SeatRepository seats) { this.seats = seats; }

    @Transactional
    public void reserve(Long seatId, String userId) {
        Seat seat = seats.findById(seatId).orElseThrow();
        seat.reserve(userId);
        // On commit: UPDATE ... WHERE version=5. If 0 rows, Spring throws
        // ObjectOptimisticLockingFailureException.
    }
}
```

Handle the conflict **outside** the transaction (each retry needs a fresh transaction and a fresh read):

```java
@Service
public class ReservationFacade {
    private final ReservationService service;
    public ReservationFacade(ReservationService service) { this.service = service; }

    // Spring Framework 7 (needs @EnableResilientMethods on a config class)
    @Retryable(includes = ObjectOptimisticLockingFailureException.class,
               maxRetries = 3, delay = 50)
    public void reserveWithRetry(Long seatId, String userId) {
        service.reserve(seatId, userId);   // retried calls re-read the seat (new version)
    }
}
```

On retry, the loser re-reads the seat, sees it is `RESERVED`, and gets a clean `SeatAlreadyTakenException`, which you map to HTTP 409.

**Use when:** conflicts are rare (different users book different seats 99% of the time). Cheap, scalable, no deadlocks.
**Avoid when:** the *same* row is hammered constantly (a hot wallet, a flash sale on one product). You'd get retry storms. Use pessimistic locking.

---

## 2. Pessimistic DB lock (`SELECT ... FOR UPDATE`)

**Simple idea:** The first transaction locks the row. Everyone else waits until it commits.

**Real case: debiting a customer wallet.** Money must never go negative, and the same wallet may get many simultaneous debits (subscription renewal + a purchase + a refund).

```java
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)                       // SELECT ... FOR UPDATE
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select w from Wallet w where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}

@Service
public class WalletService {
    private final WalletRepository wallets;
    public WalletService(WalletRepository wallets) { this.wallets = wallets; }

    @Transactional
    public void debit(Long walletId, long amountCents) {
        // Other pods trying the same wallet BLOCK here until we commit (max 3s)
        Wallet w = wallets.findByIdForUpdate(walletId).orElseThrow();
        if (w.getBalanceCents() < amountCents) throw new InsufficientFundsException();
        w.setBalanceCents(w.getBalanceCents() - amountCents);
    }   // commit releases the lock
}
```

```
Pod 1: FOR UPDATE wallet 7 ─► gets lock ─► debit ─► COMMIT (lock released)
Pod 2: FOR UPDATE wallet 7 ─► waits ───────────────► gets lock ─► debit ─► COMMIT
```

**Rules:** keep the transaction **short** (no HTTP calls inside), always set a **lock timeout**, and always lock rows in the **same order** (otherwise: deadlock).

---

## 3. `ReentrantLock`: a lock inside ONE JVM

**Simple idea:** A key to a room, but only for threads of **this** process. "Reentrant" means the thread holding the key can walk through another door that needs the same key without deadlocking itself.

**It does NOT protect you across pods.** Pod 1's lock is invisible to Pod 2. Never use it to prevent double booking.

```
 Pod 1: ReentrantLock(seat 12A) ✔ ┐
 Pod 2: ReentrantLock(seat 12A) ✔ ┘  ← two different lock objects, no protection
```

**Real use:** protecting in-memory state of one pod, for example refreshing a cached payment-gateway auth token. Without a lock, 200 threads would all call the auth server when the token expires.

```java
@Component
public class GatewayTokenProvider {
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String token;
    private volatile Instant expiresAt = Instant.EPOCH;

    public String getToken() throws InterruptedException {
        if (Instant.now().isBefore(expiresAt)) return token;       // fast path, no lock

        // tryLock with timeout: never let a payment thread wait forever
        if (!lock.tryLock(2, TimeUnit.SECONDS)) throw new IllegalStateException("Token refresh busy");
        try {
            if (Instant.now().isBefore(expiresAt)) return token;   // re-check: someone refreshed
            this.token = callAuthServer();                         // only ONE thread does this
            this.expiresAt = Instant.now().plus(Duration.ofMinutes(25));
            return token;
        } finally {
            lock.unlock();                                         // always, in finally
        }
    }
    private String callAuthServer() { return "tok_abc"; }
}
```

Reentrancy in action: `refreshAll()` takes the lock and calls `getToken()`, which takes the same lock again. The same thread passes, the hold count goes to 2, and it needs two `unlock()` calls.

---

## 4. `ReentrantReadWriteLock`: many readers, one writer

**Simple idea:** A library reading room. Any number of people can read at once. When someone needs to rewrite the book, everyone else leaves.

**Real use:** in-memory **fee rules** (commission 2.5% for cards, 1% for mobile money). Read on every payment (thousands per second), updated a few times a day.

```java
@Component
public class FeeRules {
    private final ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
    private Map<String, BigDecimal> rates = new HashMap<>();

    public BigDecimal rateFor(String method) {
        rw.readLock().lock();                 // thousands of threads in parallel
        try { return rates.getOrDefault(method, BigDecimal.ZERO); }
        finally { rw.readLock().unlock(); }
    }

    @Scheduled(fixedDelayString = "PT5M")
    public void reload() {
        Map<String, BigDecimal> fresh = loadFromDb();   // slow work OUTSIDE the lock
        rw.writeLock().lock();                          // brief exclusive swap
        try { this.rates = fresh; }
        finally { rw.writeLock().unlock(); }
    }
    private Map<String, BigDecimal> loadFromDb() { return Map.of("CARD", new BigDecimal("0.025")); }
}
```

**Traps:** you cannot upgrade a read lock to a write lock (instant deadlock); release the read lock first. If reads dominate this heavily, a `volatile` reference to an **immutable** map (swapped atomically) is often simpler and faster than any lock.

---

## 5. `StampedLock`: reads with no lock at all

**Simple idea:** "Read first, then check if anyone wrote while I was reading." Optimistic locking, in memory. Fastest for extremely read-heavy data.

**Real use:** an in-memory counter of remaining tickets shown on a page hit 50,000 times per second.

```java
@Component
public class StockView {
    private final StampedLock sl = new StampedLock();
    private int available = 100;

    public int read() {
        long stamp = sl.tryOptimisticRead();     // no lock acquired, just a stamp
        int value = available;                   // read freely
        if (!sl.validate(stamp)) {               // did a writer sneak in?
            stamp = sl.readLock();               // yes: fall back to a real read lock
            try { value = available; }
            finally { sl.unlockRead(stamp); }
        }
        return value;
    }

    public void update(int newValue) {
        long stamp = sl.writeLock();
        try { available = newValue; }
        finally { sl.unlockWrite(stamp); }       // pass the stamp, not the lock
    }
}
```

**Traps:** it is **not reentrant** (re-locking yourself = deadlock) and has no `Condition`s. Use `ReentrantReadWriteLock` unless profiling proves you need this.

---

## 6. Virtual threads + `@ConcurrencyLimit` + Bulkhead

These are **not locks on data**. They answer: *"how many calls may hit a fragile dependency at once?"*

**Scenario:** Black Friday. Your payment gateway accepts about 50 concurrent calls. With **virtual threads**, Tomcat can serve 10,000 requests at once. Great, until all 10,000 call the gateway:

```
 Without limit:   10,000 virtual threads ──► Gateway (cap 50) ──► timeouts, errors, cascade
 With limit:      10,000 virtual threads ──► [ gate: 50 inside ] ──► Gateway ✔
                                              others wait or get rejected quickly
```

Virtual threads removed the *thread* bottleneck, so now **you** must protect the real bottlenecks (gateway, DB connection pool).

```yaml
spring:
  threads:
    virtual:
      enabled: true      # Tomcat + @Async + @Scheduled use virtual threads
```

**Option A: `@ConcurrencyLimit` (Spring Framework 7, built in, simple):**

```java
@Configuration
@EnableResilientMethods
class ResilienceConfig {}

@Service
public class GatewayClient {
    @ConcurrencyLimit(40)           // max 40 simultaneous calls IN THIS POD; extra callers wait
    public ChargeResult charge(String paymentId, long cents) {
        return restClient.post()...;   // blocking call: fine on a virtual thread
    }
}
```

**Option B: Resilience4j Bulkhead (max wait, fallback, metrics):**

```java
@Bulkhead(name = "gateway", type = Bulkhead.Type.SEMAPHORE, fallbackMethod = "busy")
public ChargeResult charge(String paymentId, long cents) { ... }

ChargeResult busy(String paymentId, long cents, BulkheadFullException e) {
    return ChargeResult.retryLater();      // HTTP 503 + Retry-After, instead of piling up
}
```

```yaml
resilience4j.bulkhead.instances.gateway:
  max-concurrent-calls: 40
  max-wait-duration: 200ms     # waits 200ms max, then rejects
```

`@ConcurrencyLimit` = simple "no more than N at once". Bulkhead adds fail-fast on long waits, a fallback, and metrics. For payments, prefer the Bulkhead so users get a quick "try again" instead of a 30-second hang.

**The limit is per pod.** 5 pods × 40 = 200 concurrent calls. Set the per-pod number to *gateway capacity ÷ pod count*.

**Virtual-thread tip:** on Java 21-23, a thread blocked inside `synchronized` pins its carrier thread. Prefer `ReentrantLock` around blocking code.

---

## 7. `LockRegistry`: a lock shared by ALL pods

**Simple idea:** `ReentrantLock`, but stored in Redis (or the DB) so every pod sees the same lock. It returns the standard `java.util.concurrent.locks.Lock`, so your code looks identical.

**Real case: payment webhooks.** The bank sends the same "payment confirmed" webhook twice, and the load balancer sends the copies to **different pods** at the same time. You must confirm the order once.

```
 Webhook #1 ► Pod 1 ─┐   lock("order:991") in Redis
 Webhook #2 ► Pod 3 ─┘   → one gets it, the other waits or skips
```

```xml
<dependency>
  <groupId>org.springframework.integration</groupId>
  <artifactId>spring-integration-redis</artifactId>
</dependency>
```

```java
@Configuration
class LockConfig {
    @Bean
    RedisLockRegistry lockRegistry(RedisConnectionFactory cf) {
        // "ticketnow-locks" = key prefix, 30_000 ms = lock auto-expires if the pod dies
        return new RedisLockRegistry(cf, "ticketnow-locks", 30_000L);
    }
}

@Service
public class PaymentWebhookService {
    private final RedisLockRegistry registry;
    private final OrderRepository orders;

    public PaymentWebhookService(RedisLockRegistry registry, OrderRepository orders) {
        this.registry = registry;
        this.orders = orders;
    }

    public void onPaymentConfirmed(String orderId) throws InterruptedException {
        Lock lock = registry.obtain("order:" + orderId);       // same key => same lock on every pod
        if (!lock.tryLock(3, TimeUnit.SECONDS)) {
            return;                                            // another pod is handling it
        }
        try {
            Order order = orders.findById(orderId).orElseThrow();
            if (order.isPaid()) return;                        // idempotency check INSIDE the lock
            order.markPaid();
            orders.save(order);
        } finally {
            lock.unlock();
        }
    }
}
```

**Critical caveats:**
1. The lock **expires** (TTL). If your work outlasts 30 s, or a long GC pause happens, the lock can vanish while you still believe you hold it, and a second pod enters. That's why the `isPaid()` check and the `@Version` column remain the **real** safety net.
2. A distributed lock costs a network round trip. Use it for **coordination** (webhooks, one-pod-only jobs), not for every row update.
3. Keep the scope small: lock by `order:991`, not one global lock.

---

## How they fit together: one reservation request

```
 HTTP request (virtual thread)
   │
   ├─ @ConcurrencyLimit / Bulkhead ── protects the payment gateway (per pod)
   │
   ├─ FeeRules (ReadWriteLock)    ── in-memory read of commission, per pod
   │
   ├─ LockRegistry("order:991")   ── only one pod handles this order's webhook
   │
   └─ @Transactional
        ├─ Seat @Version          ── no double booking (optimistic, retry on conflict)
        └─ Wallet FOR UPDATE      ── no double spending (pessimistic, short tx)
```

## Decision table

| Question | Use |
|---|---|
| Two users book the same seat, conflicts are rare | `@Version` + retry |
| Same wallet or stock row hit constantly, money involved | `FOR UPDATE` (pessimistic) with timeout |
| Protect an in-memory structure inside one pod | `ReentrantLock` |
| In-memory data, many reads, rare writes | `ReentrantReadWriteLock` (or immutable `volatile` swap) |
| Ultra-hot read path, profiled need | `StampedLock` |
| Don't overload gateway or DB pool | `@ConcurrencyLimit` or Bulkhead |
| Only one pod across the cluster should do X (webhook, job) | `LockRegistry` (or ShedLock for `@Scheduled`) |

**Golden rule:** Java locks (`ReentrantLock`, `ReadWriteLock`, `StampedLock`) protect **memory inside one pod**. For **business correctness** across pods, the database (`@Version`, `FOR UPDATE`, unique constraints) is your source of truth, and `LockRegistry` only reduces duplicate work on top of it.
