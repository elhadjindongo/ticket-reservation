# The `orders` table, explained

> Part of the TicketNow exercise (`ticketnow-exercise.md`). Related guide: `locks-guide.md`.

## Why it exists

Think of a shop. The **seat** is the item on the shelf, the **wallet** is the customer's money, and the **order** is the **receipt/contract** that links them: *"Awa wants seat 12A for 2,000 cents."*

- `seat` answers: "Is this seat available?"
- `wallet` answers: "Does this user have money?"
- `orders` answers: **"What did this user try to buy, and how far did the purchase get?"**

Without `orders`, if the server crashes between "money debited" and "seat marked sold", you'd have no record of what was in progress, so you couldn't repair it.

```
 seat 12A ◄────┐
               │ seat_id
            ┌──┴─────┐        user_id        ┌────────────┐
            │ orders │ ────────────────────► │ wallet     │
            └──┬─────┘                       └────────────┘
               │ order_id
        ┌──────┴────────┐
        │ payment_audit │   (one row each time a confirmation is processed)
        └───────────────┘
```

## Column by column

```
orders(id, seat_id, user_id, amount_cents, status, idempotency_key UNIQUE, version)
```

| Column | Meaning | Example |
|---|---|---|
| `id` | Unique order number | `991` |
| `seat_id` | Which seat is being bought | `12` (seat 12A) |
| `user_id` | Who is buying | `awa` |
| `amount_cents` | Price at the time of purchase (stored, because the price may change later) | `2000` |
| `status` | Where the purchase is in its life | `PENDING`, `PAID`, `FAILED`, `REFUNDED` |
| `idempotency_key` | A unique code sent by the client for **this purchase attempt** | `"a1b2-c3d4"` |
| `version` | The `@Version` column (optimistic locking, same idea as on `seat`) | `3` |

## The life of an order (the `status` column)

```
 user clicks Pay
      │
      ▼
  PENDING ──► wallet debited ──► gateway called ──► webhook arrives
      │                              │                    │
      │                              │ fails              ▼
      │                              ▼                  PAID  ✔
      │                           FAILED ──► wallet refunded ──► REFUNDED
      ▼
  (crash? a background job finds old PENDING orders and repairs them)
```

Example rows over time for Awa's purchase:

```
 id  seat_id  user_id  amount  status    idempotency_key  version
 991   12      awa      2000   PENDING   a1b2-c3d4           0      ← created when she clicks Pay
 991   12      awa      2000   PAID      a1b2-c3d4           1      ← after the webhook confirms
```

The same row changes status; it does not create new rows.

## The most important column: `idempotency_key UNIQUE`

**Problem:** Awa's phone has bad network. She taps "Pay", nothing seems to happen, so she taps again, or the app automatically retries. Your server receives **two requests for the same purchase**. Without protection she is charged twice.

**Solution:** The client generates one random code per *purchase intention* and sends it in a header (`Idempotency-Key: a1b2-c3d4`) on every attempt, including retries. The database has a **unique constraint** on that column:

```
 Request 1: INSERT order (key = a1b2-c3d4)  → OK, order created
 Request 2: INSERT order (key = a1b2-c3d4)  → ❌ unique violation
                                              → you catch it and return the SAME response
                                                as request 1, with no second debit
```

This is why Challenge 10 asks: *"Replaying the same Idempotency-Key returns the same response and creates no second order or debit."* The unique constraint works across all pods, because the database enforces it. It is the same "database is the source of truth" principle as `@Version`.

## How it connects to the challenges

- **Challenge 9 (webhooks):** the duplicate webhook finds the order already `PAID` (`order.isPaid()`) and does nothing. The `orders` row is what you check inside the lock.
- **Challenge 10 (compensation):** if the gateway fails after the wallet was debited, the order's `status` tells you to refund and release the seat.
- **`version`:** two webhooks hitting the same order at the same time? The second `UPDATE ... WHERE version=?` fails, so the order cannot be confirmed twice.

## Minimal JPA entity

```java
@Entity
@Table(name = "orders")   // "order" is a reserved SQL word, hence "orders"
public class Order {
    @Id @GeneratedValue private Long id;

    private Long seatId;
    private String userId;
    private long amountCents;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;            // PENDING, PAID, FAILED, REFUNDED

    @Column(unique = true, nullable = false)
    private String idempotencyKey;         // the DB enforces "only one order per attempt"

    @Version
    private Long version;

    public boolean isPaid() { return status == OrderStatus.PAID; }
    public void markPaid() { this.status = OrderStatus.PAID; }
}
```

## One-sentence summary

`orders` is the **record of one purchase attempt and its progress**; `status` tracks the progress, `idempotency_key UNIQUE` prevents charging twice for the same click, and `version` prevents two threads from confirming it twice.
