package com.ndongoel.ticketreservationpayment.orders.entity;

import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import com.ndongoel.ticketreservationpayment.orders.enums.OrdersStatus;
import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Collection;

/**
 * see orders.md
 */
@Entity
public class Orders {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
//orders(id, seat_id, user_id, amount_cents, status, idempotency_key UNIQUE, version)

    @Enumerated(EnumType.STRING)
    private OrdersStatus status;
    @OneToMany
    private Collection<Seat> seat;
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    TicketUser ticketUser;


    public TicketUser getTicketUser() {
        return ticketUser;
    }

    public void setTicketUser(TicketUser ticketUser) {
        this.ticketUser = ticketUser;
    }

    public Collection<Seat> getSeat() {
        return seat;
    }

    public void setSeat(Collection<Seat> seat) {
        this.seat = seat;
    }

    public OrdersStatus getStatus() {
        return status;
    }

    public void setStatus(OrdersStatus status) {
        this.status = status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
