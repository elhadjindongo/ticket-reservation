package com.ndongoel.ticketreservationpayment.event.repository;

import com.ndongoel.ticketreservationpayment.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;


public interface EventRepository extends JpaRepository<Event, Long> {
}
