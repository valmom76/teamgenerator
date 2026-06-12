package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.entity.Subscription;
import com.boraver.teamgenerator.repository.SubscriptionRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;


@Component
public class SubscriptionChecker {

  private final SubscriptionRepository subscriptionRepo;

  public SubscriptionChecker(SubscriptionRepository subscriptionRepo) {
    this.subscriptionRepo = subscriptionRepo;
  }

  public boolean isActive() {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    return subscriptionRepo.existsByTenantIdAndStatus(tenantId, Subscription.SubscriptionStatus.ACTIVE);
  }
}