package com.ndongoel.ticketreservationpayment.event.service;


import com.ndongoel.ticketreservationpayment.event.entity.Event;
import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import com.ndongoel.ticketreservationpayment.event.enums.SeatStatus;
import com.ndongoel.ticketreservationpayment.event.repository.EventRepository;
import com.ndongoel.ticketreservationpayment.event.repository.SeatRepository;
import com.ndongoel.ticketreservationpayment.orders.dto.OrdersDto;
import com.ndongoel.ticketreservationpayment.orders.entity.Orders;
import com.ndongoel.ticketreservationpayment.orders.repository.OrdersRepository;
import com.ndongoel.ticketreservationpayment.user.repository.TicketUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

//A reservation is when user clicked on reserved button for one or many seat
//a reservation is just a lock on a seat until a certain period aka 5min in this exercise until payment
@Service
public class EventService {

    private final OrdersRepository ordersRepository;
    private final SeatRepository seatRepository;
    private final TicketUserRepository ticketUserRepository;
    private final EventRepository eventRepository;

    public EventService(OrdersRepository ordersRepository, SeatRepository seatRepository, TicketUserRepository ticketUserRepository, EventRepository eventRepository) {
        this.ordersRepository = ordersRepository;
        this.seatRepository = seatRepository;
        this.ticketUserRepository = ticketUserRepository;
        this.eventRepository = eventRepository;
    }

    //run a timer (seat in status HELD) that is stopped by payment or canceled or expire (then Seat is FREE)
    //No when getting available seat fetch by hold
    //a reservation create a pending prder and waits for payments
    @Transactional(rollbackFor = Exception.class)
    public Orders reserveSeat(OrdersDto ordersDto, long eventID) {
        Orders orders = new Orders();
        //fetch if user exist
        var ticketUser = ticketUserRepository.findById(ordersDto.getUserID()).orElseThrow(() -> new RuntimeException("User not found !!!"));
        orders.setTicketUser(ticketUser.getId());
        //fetch each seat if exist the held and add amount
        List<Seat> seats = new ArrayList<>();
        BigDecimal amount = ordersDto.getSeats().stream().map(seatDto -> {
            var seat = seatRepository.findSeatByIdAndStatus(seatDto.getId(), SeatStatus.FREE).orElseThrow(() -> new RuntimeException("Seat not found !!!"));
            if (seat.getEventID() != eventID) {
                throw new RuntimeException("The seat %s is not in this event !!!".formatted(seat.getLabel()));
            }
            seat.setStatus(SeatStatus.HELD);
            seat.setHeld_until(LocalDateTime.now().plusMinutes(10)); //A la recuperation je recupere tout avec  held_until <= now puis lancer un async qui remet a free et null le held
           seat.setReserved_by(ticketUser.getId());
            seats.add(seatRepository.save(seat));
            return seat.getPrice();
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
        orders.setSeat(seats);
        orders.setStatus(Orders.Status.PENDING);
        orders.setAmount(amount);
        System.out.println("----------- orders: "+orders);
        return ordersRepository.save(orders);
    }


    public Collection<Event> getEvents() {
        return eventRepository.findAll();
    }

    public Collection<Seat> getSeats(long eventID, String status) {
        //fixme check first if event exists
        if (null == status) {
            return seatRepository.findAllByEventID(eventID);
        }
        try {
            SeatStatus seatStatus = SeatStatus.valueOf(status);
            if (seatStatus == SeatStatus.FREE) {
                return seatRepository.findFreeSeatByEventID(eventID, LocalDateTime.now());
            } else {
                return seatRepository.findAllByEventIDAndStatus(eventID, seatStatus);
            }

        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Invalid status !!!");
        }

    }
}
