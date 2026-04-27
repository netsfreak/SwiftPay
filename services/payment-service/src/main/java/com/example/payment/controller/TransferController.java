package com.example.payment.controller;

import com.example.payment.model.PaymentDtos.TransferRequest;
import com.example.payment.model.PaymentDtos.TransferResponse;
import com.example.payment.service.PaymentOrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final PaymentOrchestratorService paymentOrchestratorService;

    public TransferController(PaymentOrchestratorService paymentOrchestratorService) {
        this.paymentOrchestratorService = paymentOrchestratorService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestBody TransferRequest request) {
        return ResponseEntity.ok(paymentOrchestratorService.transfer(request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
