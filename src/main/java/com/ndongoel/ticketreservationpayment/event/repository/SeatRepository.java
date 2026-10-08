package com.ndongoel.ticketreservationpayment.event.repository;

import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import com.ndongoel.ticketreservationpayment.event.enums.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {

   @Query("select s from Seat s  where s.eventID = :eventId  and (s.held_until <= :heldDate or s.status = 'FREE')")
   List<Seat> findFreeSeatByEventID(@Param("eventId") long eventId,@Param("heldDate") LocalDateTime heldDate);

   Optional<Seat> findSeatByIdAndStatus(long eventID, SeatStatus status);
}