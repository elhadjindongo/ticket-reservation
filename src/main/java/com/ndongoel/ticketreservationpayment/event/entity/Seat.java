package com.ndongoel.ticketreservationpayment.event.entity;


import com.ndongoel.ticketreservationpayment.event.enums.SeatStatus;
import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter @Setter
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
    long reserved_by;
    LocalDateTime held_until;

    public Seat() {
        status = SeatStatus.FREE;
    }

}
