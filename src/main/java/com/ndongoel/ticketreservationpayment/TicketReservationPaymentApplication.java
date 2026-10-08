package com.ndongoel.ticketreservationpayment;

import com.ndongoel.ticketreservationpayment.event.entity.Event;
import com.ndongoel.ticketreservationpayment.event.entity.Seat;
import com.ndongoel.ticketreservationpayment.event.repository.EventRepository;
import com.ndongoel.ticketreservationpayment.event.repository.SeatRepository;
import com.ndongoel.ticketreservationpayment.user.model.TicketUser;
import com.ndongoel.ticketreservationpayment.user.repository.TicketUserRepository;
import com.ndongoel.ticketreservationpayment.wallet.enums.WalletStatus;
import com.ndongoel.ticketreservationpayment.wallet.model.Wallet;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

@SpringBootApplication
public class TicketReservationPaymentApplication implements CommandLineRunner {

    private final SeatRepository seatRepository;

    public TicketReservationPaymentApplication(EventRepository eventRepository, TicketUserRepository ticketUserRepository, SeatRepository seatRepository) {
        this.eventRepository = eventRepository;
        this.ticketUserRepository = ticketUserRepository;
        this.seatRepository = seatRepository;
    }

    public static void main(String[] args) {
        SpringApplication.run(TicketReservationPaymentApplication.class, args);
    }

    private final EventRepository eventRepository;
    private final TicketUserRepository ticketUserRepository;
    @Override
    public void run(String... args) throws Exception {
        var event = new Event();
        event.setTitle("The Alamo show");
        event.setDescription("The Alamo show description");
        event.setDate(LocalDateTime.now());
        event.setLocation("The Alamo location");
        eventRepository.save(event);

        for (int i = 1; i <= 10; i++) {
            var seat = new Seat();
            seat.setEvent(event.getId());
            seat.setLabel("Seat " + i);
            seat.setPrice(BigDecimal.TEN.multiply(BigDecimal.valueOf(i)));
            seatRepository.save(seat);
        }

        var ticketUser = new TicketUser();
        ticketUser.setFullName("El Hadji NDONGO");
        var wallet = new Wallet();
        wallet.setBalanceCents(BigDecimal.valueOf(100));
        wallet.setStatus(WalletStatus.ACTIVE);
        wallet.setUser(ticketUser);
        ticketUser.setWallet(wallet);
        ticketUserRepository.save(ticketUser);

    }
}
