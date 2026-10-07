package com.ndongoel.ticketreservationpayment.wallet.model;

import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import com.ndongoel.ticketreservationpayment.wallet.enums.WalletStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/*
* See wallet.md
* */
@Entity
public class Wallet {
    @Id  @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal balanceCents;
    @Enumerated(EnumType.STRING)
    private WalletStatus status;

    @OneToOne
    @NotBlank
    private TicketUser user;

    public void debit(BigDecimal cents) {
        if (status != WalletStatus.ACTIVE) throw new RuntimeException("Wallet is not active");
        if (cents.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("amount must be positive");
        if (balanceCents.compareTo(cents) < 0) throw new RuntimeException("balance is unsufficient !");
       var newBalance=  balanceCents.subtract(cents);
       setBalanceCents(newBalance);
    }

    public void credit(BigDecimal cents) {
        if (cents.compareTo(BigDecimal.ZERO) <= 0) throw new IllegalArgumentException("amount must be positive");
        var newBalance=  balanceCents.add(cents);
        setBalanceCents(newBalance);
    }


    public BigDecimal getBalanceCents() {
        return balanceCents;
    }

    public void setBalanceCents(BigDecimal balanceCents) {
        this.balanceCents = balanceCents;
    }

    public TicketUser getUser() {
        return user;
    }

    public void setUser(TicketUser user) {
        this.user = user;
    }

    public WalletStatus getStatus() {
        return status;
    }

    public void setStatus(WalletStatus status) {
        this.status = status;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
