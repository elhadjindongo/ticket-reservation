package com.ndongoel.ticketreservationpayment.orders.dto;

import jakarta.validation.constraints.Min;

public class SeatDto {
    @Min(1)
    long id;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }
}
