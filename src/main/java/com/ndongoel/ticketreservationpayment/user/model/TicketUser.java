package com.ndongoel.ticketreservationpayment.user.model;

import com.ndongoel.ticketreservationpayment.orders.entity.Orders;
import com.ndongoel.ticketreservationpayment.wallet.model.Wallet;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Setter
@Getter
@Entity
public class TicketUser {
    @Id  @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    @OneToOne(cascade = CascadeType.ALL)
    private Wallet wallet;

    @OneToMany(mappedBy = "ticketUserID")
    private List<Orders> orders = new ArrayList<>();


}
