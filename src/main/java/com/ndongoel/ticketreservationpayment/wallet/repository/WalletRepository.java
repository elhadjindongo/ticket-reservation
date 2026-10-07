package com.ndongoel.ticketreservationpayment.wallet.repository;


import com.ndongoel.ticketreservationpayment.wallet.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
}
