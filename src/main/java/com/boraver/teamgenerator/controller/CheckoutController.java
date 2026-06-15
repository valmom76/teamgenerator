package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.dto.subscription.CheckoutResponseDTO;
import com.boraver.teamgenerator.dto.subscription.SubscribeRequestDTO;
import com.boraver.teamgenerator.dto.subscription.SubscriptionStatusDTO;
import com.boraver.teamgenerator.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkout")
public class CheckoutController {

  private final SubscriptionService subscriptionService;

  public CheckoutController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @PostMapping("/subscribe")
  public ResponseEntity<CheckoutResponseDTO> subscribe(@RequestBody SubscribeRequestDTO request) {
    return ResponseEntity.ok(subscriptionService.subscribe(request));
  }

  @PostMapping("/downgrade")
  public ResponseEntity<CheckoutResponseDTO> downgrade(@RequestBody SubscribeRequestDTO request) {
    return ResponseEntity.ok(subscriptionService.downgrade(request));
  }

  @GetMapping("/status")
  public ResponseEntity<SubscriptionStatusDTO> status() {
    return ResponseEntity.ok(subscriptionService.getSubscriptionStatus());
  }
}