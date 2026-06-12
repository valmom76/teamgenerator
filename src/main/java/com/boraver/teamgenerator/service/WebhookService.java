package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.entity.Subscription;
import com.boraver.teamgenerator.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class WebhookService {

  private final SubscriptionRepository subscriptionRepo;

  public WebhookService(SubscriptionRepository subscriptionRepo) {
    this.subscriptionRepo = subscriptionRepo;
  }

  @Transactional
  public void processAsaasEvent(Map<String, Object> payload) {
    String event = (String) payload.get("event");
    Map<String, Object> payment = (Map<String, Object>) payload.get("payment");

    if (payment != null) {
      String subscriptionId = (String) payment.get("subscription");
      Subscription sub = subscriptionRepo.findByAsaasSubscriptionId(subscriptionId);
      if (sub == null) return;

      switch (event) {
        case "PAYMENT_CONFIRMED" -> sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
        case "PAYMENT_OVERDUE" -> sub.setStatus(Subscription.SubscriptionStatus.SUSPENDED);
        case "PAYMENT_DELETED" -> sub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
      }
      subscriptionRepo.save(sub);
    } else if ("SUBSCRIPTION_CANCELED".equals(event)) {
      Map<String, Object> subData = (Map<String, Object>) payload.get("subscription");
      String subId = (String) subData.get("id");
      Subscription sub = subscriptionRepo.findByAsaasSubscriptionId(subId);
      if (sub != null) {
        sub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
        subscriptionRepo.save(sub);
      }
    }
  }
}