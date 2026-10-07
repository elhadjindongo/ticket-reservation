package com.ndongoel.ticketreservationpayment.user.repository;


import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketUserRepository extends JpaRepository<TicketUser,Long> {
}
