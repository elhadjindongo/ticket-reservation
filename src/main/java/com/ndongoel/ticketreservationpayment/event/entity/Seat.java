package com.ndongoel.ticketreservationpayment.event.entity;


import com.ndongoel.ticketreservationpayment.event.enums.SeatStatus;
import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
public class Seat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //    seat(id, event_id, label, status, reserved_by, held_until, version)
    @Column(nullable = false, name = "event_id")
    long eventID;
    String label;
    @Enumerated(EnumType.STRING)
    SeatStatus status;
    BigDecimal price;
    @ManyToOne(fetch = FetchType.LAZY)
    TicketUser reserved_by;
    LocalDateTime held_until;

    public Seat() {
        status = SeatStatus.FREE;
    }

    public void setReserved_by(TicketUser reserved_by) {
        this.reserved_by = reserved_by;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public long getEvent() {
        return eventID;
    }

    public void setEvent(long eventID) {
        this.eventID = eventID;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = status;
    }

    public LocalDateTime getHeld_until(LocalDateTime localDateTime) {
        return held_until;
    }

    public void setHeld_until(LocalDateTime held_until) {
        this.held_until = held_until;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
