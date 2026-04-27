package com.example.ppps.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.example.ppps.event.LedgerEventPublisher;
import com.example.ppps.repository.TransactionRepository;
import com.example.ppps.repository.UserRepository;
import com.example.ppps.repository.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

class TransferServiceTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FeeService feeService;

    @Mock
    private GatewayService gatewayService;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private EscrowService escrowService;

    @Mock
    private LedgerEventPublisher ledgerEventPublisher;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        transferService = new TransferService(
            walletRepository,
            transactionRepository,
            userRepository,
            entityManager,
            new SimpleMeterRegistry(),
            passwordEncoder,
            feeService,
            gatewayService,
            kafkaTemplate,
            escrowService,
            ledgerEventPublisher,
            java.util.UUID.randomUUID().toString()
        );
    }

    @Test
    void setUp_initializesService() {
        assertNotNull(transferService);
    }
}
