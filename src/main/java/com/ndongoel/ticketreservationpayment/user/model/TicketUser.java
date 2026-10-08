package com.ndongoel.ticketreservationpayment.user.model;

import com.ndongoel.ticketreservationpayment.orders.entity.Orders;
import com.ndongoel.ticketreservationpayment.wallet.model.Wallet;
import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
public class TicketUser {
    @Id  @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String fullName;

    @OneToOne(cascade = CascadeType.PERSIST)
    private Wallet wallet;

    @OneToMany(mappedBy = "ticketUserID")
    private List<Orders> orders = new ArrayList<>();


    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public void setWallet(Wallet wallet) {
        this.wallet = wallet;
    }

    public List<Orders> getOrders() {
        return orders;
    }

    public void setOrders(List<Orders> orders) {
        this.orders = orders;
    }
}
