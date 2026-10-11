package com.ndongoel.ticketreservationpayment.event.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;

@Setter
@Getter
@Entity
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;
    private String description;
    private String location;
    private LocalDateTime date;
    @OneToMany(cascade = CascadeType.ALL, mappedBy = "eventID")
    private Collection<Seat> seats = new ArrayList<>();


}
