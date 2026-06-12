package com.boraver.teamgenerator.controller;

import com.boraver.teamgenerator.service.WebhookService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {

  private final WebhookService webhookService;
  @Value("${asaas.webhook-secret}")
  private String secret;

  public WebhookController(WebhookService webhookService) {
    this.webhookService = webhookService;
  }

  @PostMapping("/asaas")
  public ResponseEntity<String> handle(
    @RequestBody Map<String, Object> payload,
    @RequestHeader("asaas-access-token") String token) {
    if (!secret.equals(token)) return ResponseEntity.status(403).body("Token inválido");
    webhookService.processAsaasEvent(payload);
    return ResponseEntity.ok("OK");
  }
}