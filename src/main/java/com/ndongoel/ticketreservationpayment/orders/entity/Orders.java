package com.ndongoel.ticketreservationpayment.orders.entity;

import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * see orders.md
 */
/**
 * A purchase: it HOLDS its seats from the moment it is created, then gets paid or releases them.
 * (Table is "orders" because ORDER is a reserved SQL word.)
 *
 * <pre>
 *   POST /orders ──► HELD ──pay──► PAYING ──ok──► PAID ──refund──► REFUNDED
 *                     │  ▲           │
 *                     │  └─declined──┘            (payment failed before any debit: retry allowed)
 *                     ├─ expires_at passed (job) ─► EXPIRED
 *                     └─ user cancels / compensation ─► CANCELLED
 * </pre>
 *
 * The seat collection is the INVERSE side (mappedBy): the foreign key lives on seat.order_id.
 * Holding a seat means UPDATE seat SET order_id = ? ; releasing it means order_id = NULL.
 *
 * Protections (all enforced by the database, so they work across every pod):
 *  - seats:        @Version on Seat (or ordered FOR UPDATE) -> one winner per seat, all-or-nothing
 *  - double click: UNIQUE(idempotency_key)                  -> one order per client attempt
 *  - races:        @Version on Order                        -> pay vs expire vs cancel vs webhook
 *
 * Why PAYING exists: the expiry job only touches HELD orders. Once a payment is in flight the
 * order is PAYING, so the job can never expire it at the very moment the gateway charges.
 * (Bonus: a separate job for orders stuck in PAYING after a crash.)
 *
 * EXPIRY JOB: simplest is to load expired HELD orders and call expire(now) on each.
 * A bulk JPQL alternative must free the seats AND bump their version by hand, in one transaction:
 *   update Seat s set s.order = null, s.version = s.version + 1 where s.order.id in :expiredOrderIds
 * (Bulk JPQL does not bump @Version by itself; without it a concurrent hold could still commit.)
 *
 * DEADLOCK NOTE: updates/locks on several seats must always happen in ascending seat id order.
 * Load seats with ORDER BY id (and consider hibernate.order_updates=true). addSeat enforces
 * ascending order on the entity side.
 */
@Setter
@Getter
@Entity
public class Orders {
//    public enum Status { HELD, PAYING, PAID, EXPIRED, CANCELLED, REFUNDED }
    public enum Status { PENDING, PAID, EXPIRED, CANCELLED }
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
//orders(id, seat_id, user_id, amount_cents, status, idempotency_key UNIQUE, version)

    @Enumerated(EnumType.STRING)
    private Status status;
    @OneToMany
    private Collection<Seat> seat;
    private BigDecimal amount;
    @Column(name = "user_id",nullable = false)
    long ticketUserID;


    public void setTicketUser(long ticketUserID) {
        this.ticketUserID = ticketUserID;
    }

}
