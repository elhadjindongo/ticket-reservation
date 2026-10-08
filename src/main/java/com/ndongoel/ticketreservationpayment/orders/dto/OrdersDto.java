package com.ndongoel.ticketreservationpayment.orders.dto;

import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import jakarta.validation.constraints.NotBlank;

import java.util.Collection;

public class OrdersDto {
    private Long id;
    @NotBlank
    private Collection<SeatDto> seats;
    @NotBlank
    long userID;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Collection<SeatDto> getSeats() {
        return seats;
    }

    public void setSeats(Collection<SeatDto> seats) {
        this.seats = seats;
    }

    public long getUserID() {
        return userID;
    }

    public void setUserID(long userID) {
        this.userID = userID;
    }
}
