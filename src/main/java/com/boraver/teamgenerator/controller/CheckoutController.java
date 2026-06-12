package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.dto.subscription.CheckoutResponseDTO;
import com.boraver.teamgenerator.dto.subscription.SubscribeRequestDTO;
import com.boraver.teamgenerator.dto.subscription.SubscriptionStatusDTO;
import com.boraver.teamgenerator.entity.AppUser;
import com.boraver.teamgenerator.service.SubscriptionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/checkout")
public class CheckoutController {

  private final SubscriptionService subscriptionService;

  public CheckoutController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @PostMapping("/subscribe")
  public ResponseEntity<CheckoutResponseDTO> subscribe(
    @RequestBody SubscribeRequestDTO request,
    @AuthenticationPrincipal AppUser currentUser) {
    return ResponseEntity.ok(subscriptionService.subscribe(request, currentUser));
  }

  @GetMapping("/status")
  public ResponseEntity<SubscriptionStatusDTO> status() {
    return ResponseEntity.ok(subscriptionService.getSubscriptionStatus());
  }
}