# Seat and reservation model

> Part of the TicketNow exercise (`ticketnow-exercise.md`). Related: `locks-guide.md`, `orders.md`, `wallet.md`.

## Reservation vs order: two different moments

In the exercise as written, **the reservation is not in `orders`**. It lives **on the `seat` row** (`status = HELD`, `reserved_by`, `held_until`). The `orders` row is created later, when the user pays.

| | Reservation (hold) | Order |
|---|---|---|
| Meaning | "I'm holding this seat for you for 5 minutes" | "You are paying for this seat" |
| Created when | User clicks **Reserve** | User clicks **Pay** |
| Money involved | None | Yes (wallet debit, gateway charge) |
| Lifetime | Short, expires automatically | Permanent record |
| Failure mode | Expires, the seat becomes `FREE` again | `FAILED` / `REFUNDED` |

```
 t0 Reserve ───────────► t1 Pay ────────────► t2 Webhook confirms
 seat: FREE → HELD       order: PENDING        order: PAID
 (held_until = t0+5min)  wallet: debited       seat: HELD → SOLD

 If the user never pays: at held_until the seat goes back to FREE, and no order ever existed.
```

If you put the reservation into `orders`, you would create an order for every click on "Reserve", and most would be abandoned. It also mixes two lifecycles (a 5-minute hold and a permanent payment record).

## Seat life cycle

```
            reserve (user)                pay + webhook confirms
   FREE ───────────────────► HELD ─────────────────────────────► SOLD
     ▲                         │
     │   hold expires (job)    │  user cancels
     └─────────────────────────┘
```

| Transition | Triggered by | Protection |
|---|---|---|
| FREE → HELD | User clicks Reserve | `@Version` + retry (Option A) or unique partial index (Option B) |
| HELD → FREE | Expiry job or user cancel | Job runs on **one pod only** (ShedLock or `LockRegistry`) |
| HELD → SOLD | Payment confirmed (webhook) | `@Version`, idempotent handler, lock per order |
| SOLD → anything | Never (refunds are a separate flow) | Reject in the entity |

---

## Option A: the hold lives on `seat` (used by the exercise)

### SQL

```sql
CREATE TABLE seat (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT       NOT NULL,
    label        VARCHAR(16)  NOT NULL,               -- e.g. 12A
    status       VARCHAR(16)  NOT NULL DEFAULT 'FREE',-- FREE / HELD / SOLD
    reserved_by  VARCHAR(64),                         -- user holding or owning the seat
    held_until   TIMESTAMPTZ,                         -- only meaningful while HELD
    version      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_seat_label UNIQUE (event_id, label),
    -- a HELD seat must have an owner and an expiry; a FREE seat must have neither
    CONSTRAINT ck_seat_state CHECK (
        (status = 'FREE' AND reserved_by IS NULL AND held_until IS NULL) OR
        (status = 'HELD' AND reserved_by IS NOT NULL AND held_until IS NOT NULL) OR
        (status = 'SOLD' AND reserved_by IS NOT NULL)
    )
);

CREATE INDEX idx_seat_event_status ON seat(event_id, status);
CREATE INDEX idx_seat_held_until   ON seat(held_until) WHERE status = 'HELD';  -- speeds the expiry job
```

The `CHECK` constraint is a database-level guard: even a buggy code path cannot create a `HELD` seat with no owner.

### JPA entity with business rules inside

```java
@Entity
public class Seat {
    @Id @GeneratedValue private Long id;
    private Long eventId;
    private String label;

    @Enumerated(EnumType.STRING)
    private SeatStatus status = SeatStatus.FREE;

    private String reservedBy;
    private Instant heldUntil;

    @Version private Long version;

    /** FREE -> HELD */
    public void hold(String userId, Instant now, Duration holdTime) {
        // An expired hold counts as free, even if the cleanup job has not run yet
        boolean available = status == SeatStatus.FREE
                || (status == SeatStatus.HELD && heldUntil.isBefore(now));
        if (!available) throw new SeatAlreadyTakenException(id);
        this.status = SeatStatus.HELD;
        this.reservedBy = userId;
        this.heldUntil = now.plus(holdTime);
    }

    /** HELD -> FREE (expiry or cancel) */
    public void release() {
        if (status == SeatStatus.SOLD) throw new IllegalStateException("Seat already sold");
        this.status = SeatStatus.FREE;
        this.reservedBy = null;
        this.heldUntil = null;
    }

    /** Pay-time check: is this user still the valid holder? */
    public void assertHeldBy(String userId, Instant now) {
        if (status != SeatStatus.HELD
                || !userId.equals(reservedBy)
                || heldUntil.isBefore(now)) {
            throw new HoldExpiredException(id);
        }
    }

    /** HELD -> SOLD */
    public void sell(String userId, Instant now) {
        assertHeldBy(userId, now);
        this.status = SeatStatus.SOLD;
        this.heldUntil = null;
    }
}
```

Use an injected `Clock` (not `Instant.now()` inside services) so your tests can control time. Challenge 10, V10.9 (expiring holds) becomes easy to test without sleeping.

### Reserve any free seat of an event (Challenge 3)

```java
public interface SeatRepository extends JpaRepository<Seat, Long> {
    Optional<Seat> findFirstByEventIdAndStatusOrderByIdAsc(Long eventId, SeatStatus status);
}

@Service
public class ReservationService {
    private final SeatRepository seats;
    private final Clock clock;
    // constructor injection...

    @Transactional
    public Long reserveAny(Long eventId, String userId) {
        Seat seat = seats.findFirstByEventIdAndStatusOrderByIdAsc(eventId, SeatStatus.FREE)
                .orElseThrow(SoldOutException::new);
        seat.hold(userId, clock.instant(), Duration.ofMinutes(5));
        return seat.getId();          // @Version conflict is detected at commit
    }
}
```

When two users pick the same first free seat, one fails at commit with an optimistic-lock exception. The retry in the facade re-reads, finds the *next* free seat, and succeeds.

**Limitation of the query above:** expired `HELD` seats are not returned (the status is not `FREE`) until the expiry job runs. That is acceptable; the job runs every few seconds.

### The expiry job (runs on one pod only)

```java
@Component
public class HoldExpiryJob {
    private final SeatRepository seats;
    private final Clock clock;
    // constructor injection...

    @Scheduled(fixedDelayString = "PT10S")
    @SchedulerLock(name = "holdExpiry", lockAtMostFor = "PT1M", lockAtLeastFor = "PT5S")
    @Transactional
    public void releaseExpired() {
        // Bulk update is atomic and fast; @Version is bumped so concurrent
        // "pay" transactions holding an older version will fail cleanly.
        int released = seats.releaseExpired(clock.instant());
        // log + metric: seats.hold.expired
    }
}

// In SeatRepository:
@Modifying
@Query("""
    update Seat s
       set s.status = 'FREE', s.reservedBy = null, s.heldUntil = null, s.version = s.version + 1
     where s.status = 'HELD' and s.heldUntil < :now
""")
int releaseExpired(@Param("now") Instant now);
```

Bulk JPQL updates **do not bump `@Version` automatically**, which is why the query does it by hand. Without it, a payment transaction that read the seat before the job ran could still commit against the old version and sell a seat that was just released and re-held by someone else.

---

## Option B: a separate `reservation` table (common in real systems)

### SQL

```sql
CREATE TABLE reservation (
    id          BIGSERIAL PRIMARY KEY,
    seat_id     BIGINT      NOT NULL REFERENCES seat(id),
    user_id     VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,        -- ACTIVE / CONFIRMED / EXPIRED / CANCELLED
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0
);

-- The key trick: at most ONE active reservation per seat, enforced by the database
CREATE UNIQUE INDEX uq_active_reservation_per_seat
    ON reservation(seat_id) WHERE status = 'ACTIVE';

-- orders now points to the reservation it fulfills
ALTER TABLE orders ADD COLUMN reservation_id BIGINT REFERENCES reservation(id);
```

```
 seat 12A ◄── reservation (ACTIVE, awa, expires 14:35) ◄── orders (PENDING → PAID)
```

### Reserving with the unique index

```java
@Transactional
public Long reserve(Long seatId, String userId) {
    try {
        Reservation r = new Reservation(seatId, userId, Instant.now(clock).plus(HOLD));
        return reservations.saveAndFlush(r).getId();     // flush: the INSERT happens NOW
    } catch (DataIntegrityViolationException e) {
        throw new SeatAlreadyTakenException(seatId);     // someone else has an ACTIVE hold
    }
}
```

Use `saveAndFlush` so the constraint violation surfaces inside your `try`, not at commit time.

**Expired ACTIVE reservations:** the unique index still sees them as `ACTIVE` until the job flips them to `EXPIRED`. Either run the job frequently, or on reserve first expire the seat's stale reservation in the same transaction.

### Which tables link to which

```
 seat ──1:N── reservation ──1:0..1── orders ──1:N── payment_audit
```

One seat has many reservations over time (expired, cancelled, confirmed), but only one `ACTIVE` at a time. A reservation leads to at most one order.

---

## Option A vs Option B

| | Option A (on `seat`) | Option B (`reservation` table) |
|---|---|---|
| Complexity | Low | Higher (more tables and joins) |
| Prevents double booking with | `@Version` + retry | Partial unique index (DB-level) |
| Loser fails | At commit | At `INSERT` |
| History of holds | No | Yes |
| Cart with several seats | Awkward | Natural (add `cart_id`) |
| Best for | The exercise, learning the locking tools | Real products |

## Mapping to the challenges

| Challenge | What this model gives you |
|---|---|
| 1 | Naive `hold()` without `@Version`: the race you reproduce |
| 2 | `@Version` on `Seat`; one winner out of 50; 409 for the others |
| 3 | `reserveAny` + `@Retryable` facade: 10 seats sold to 50 users |
| 7 | `StockView` can be fed from `count(status = FREE)` as the in-memory snapshot |
| 9 | Webhook calls `seat.sell(...)` inside the per-order lock; idempotent |
| 10, V10.1 | Final assertion: exactly 100 `SOLD`, none twice |
| 10, V10.9 | Expiry job + `Clock` makes held-seat expiry testable |

## Validation ideas specific to the seat model

- [ ] A `HELD` seat past `held_until` can be taken by another user even before the job runs (the `hold()` rule above), and the first user's `pay` then fails with `HoldExpiredException` (409/410).
- [ ] The `CHECK` constraint rejects a direct SQL attempt to create a `HELD` seat without an owner.
- [ ] Running the expiry job on 3 simulated pods releases each expired seat **once** (ShedLock) and never touches a `SOLD` seat.
- [ ] A payment transaction that read the seat before the expiry job ran **fails** at commit (version bumped by the bulk update).
- [ ] Bonus: reimplement Challenge 2 with Option B's partial unique index and compare where the losers fail.
