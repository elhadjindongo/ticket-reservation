package com.ndongoel.ticketreservationpayment.event.controller;

import com.ndongoel.ticketreservationpayment.event.entity.Event;
import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import com.ndongoel.ticketreservationpayment.event.service.EventService;
import com.ndongoel.ticketreservationpayment.orders.dto.OrdersDto;
import com.ndongoel.ticketreservationpayment.orders.entity.Orders;
import org.springframework.data.repository.query.Param;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Collection;

@RestController
@RequestMapping("events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    Collection<Event> getEvents() {
        return eventService.getEvents();
    }

    @GetMapping("/{eventID}/seats")
    Collection<Seat> getFreeSeats(@PathVariable long eventID) {
        return eventService.getSeats(eventID);
    }

    @PostMapping("/{eventID}")
    Orders reserveSeat(@RequestBody OrdersDto ordersDto, @PathVariable long eventID){
        return eventService.reserveSeat(ordersDto, eventID);
    }
}
