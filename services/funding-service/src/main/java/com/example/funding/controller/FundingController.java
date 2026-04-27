package com.example.funding.controller;

import com.example.funding.model.FundingDtos.FundingRequest;
import com.example.funding.model.FundingDtos.FundingResponse;
import com.example.funding.service.FundingOrchestratorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/funding")
public class FundingController {
    private final FundingOrchestratorService fundingOrchestratorService;

    public FundingController(FundingOrchestratorService fundingOrchestratorService) {
        this.fundingOrchestratorService = fundingOrchestratorService;
    }

    @PostMapping("/deposits")
    public ResponseEntity<FundingResponse> deposit(@RequestBody FundingRequest request) {
        return ResponseEntity.ok(fundingOrchestratorService.deposit(request));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<FundingResponse> withdraw(@RequestBody FundingRequest request) {
        return ResponseEntity.ok(fundingOrchestratorService.withdraw(request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleException(Exception ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
