package com.ndongoel.ticketreservationpayment.wallet.model;

import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import com.ndongoel.ticketreservationpayment.wallet.enums.WalletStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/*
* See wallet.md
* */
@Setter
@Getter
@Entity
public class Wallet {
    @Id  @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private BigDecimal balanceCents;
    @Enumerated(EnumType.STRING)
    private WalletStatus status;

    @OneToOne(optional = false)
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


}
