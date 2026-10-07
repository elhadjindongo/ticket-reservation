package com.ndongoel.ticketreservationpayment.event.service;


import org.springframework.stereotype.Service;

//A reservation is when user clicked on reserved button for one or many seat
//a reservation is just a lock on a seat until a certain period aka 5min in this exercise until payment
@Service
public class ReservationService {

    // a user_id and list of seat
    //run a timer (seat in status HELD) that is stopped by payment or canceled or expire (then Seat is FREE)
}
