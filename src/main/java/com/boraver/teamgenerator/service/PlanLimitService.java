package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.entity.Plan;
import com.boraver.teamgenerator.entity.Subscription;
import com.boraver.teamgenerator.exception.LimitExceededException;
import com.boraver.teamgenerator.repository.PlanRepository;
import com.boraver.teamgenerator.repository.PlayerRepository;
import com.boraver.teamgenerator.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanLimitService {

  private final SubscriptionRepository subscriptionRepo;
  private final PlayerRepository playerRepo;
  private final PlanRepository planRepo;

  public Plan getCurrentPlan(UUID tenantId) {
    return subscriptionRepo
            .findByTenantIdAndStatus(tenantId, Subscription.SubscriptionStatus.ACTIVE)
            .map(Subscription::getPlan)
            .orElseGet(() -> planRepo.findByName("Free")
                    .orElseThrow(() -> new RuntimeException("Plano Free não encontrado")));
  }

  public void checkPlayerLimit(UUID tenantId) {
    Plan plan = getCurrentPlan(tenantId);

    if (plan.getMaxPlayers() == -1) {
      return;
    }

    long currentCount = playerRepo.countByTenantIdAndActiveTrue(tenantId);
    if (currentCount >= plan.getMaxPlayers()) {
      throw new LimitExceededException(
              "Limite de jogadores atingido (" + plan.getMaxPlayers() + "). " +
                      "Faça upgrade para cadastrar mais atletas."
      );
    }
  }
}