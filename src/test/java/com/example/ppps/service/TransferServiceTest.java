package com.example.ppps.service;

import com.example.ppps.repository.LedgerEntryRepository;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TransferServiceTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
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

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        transferService = new TransferService(
                walletRepository,
                transactionRepository,
                ledgerEntryRepository,
                userRepository,
                entityManager,
                new SimpleMeterRegistry(),
                passwordEncoder,
                feeService,
                gatewayService,
                kafkaTemplate,
                escrowService,
                java.util.UUID.randomUUID().toString()
        );
    }

    @Test
    void setUp_initializesService() {
        assertNotNull(transferService);
    }
}