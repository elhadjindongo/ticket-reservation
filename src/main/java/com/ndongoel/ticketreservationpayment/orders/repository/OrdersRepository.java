package com.ndongoel.ticketreservationpayment.orders.repository;

import com.ndongoel.ticketreservationpayment.orders.entity.Orders;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrdersRepository extends JpaRepository<Orders, Long> {
}
