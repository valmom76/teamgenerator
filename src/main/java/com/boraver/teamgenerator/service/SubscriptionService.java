package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.subscription.CheckoutResponseDTO;
import com.boraver.teamgenerator.dto.subscription.SubscribeRequestDTO;
import com.boraver.teamgenerator.dto.subscription.SubscriptionStatusDTO;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.PlanRepository;
import com.boraver.teamgenerator.repository.SubscriptionRepository;
import com.boraver.teamgenerator.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

  private final SubscriptionRepository subscriptionRepo;
  private final PlanRepository planRepo;
  private final TenantRepository tenantRepo;
  private final AsaasService asaasService;

  public SubscriptionService(SubscriptionRepository subscriptionRepo,
                             PlanRepository planRepo,
                             TenantRepository tenantRepo,
                             AsaasService asaasService) {
    this.subscriptionRepo = subscriptionRepo;
    this.planRepo = planRepo;
    this.tenantRepo = tenantRepo;
    this.asaasService = asaasService;
  }

  @Transactional
  public CheckoutResponseDTO subscribe(SubscribeRequestDTO request, AppUser currentUser) {
    UUID tenantId = UUID.fromString(TenantContext.getTenantId());
    if (tenantId.toString() == null) {
      throw new IllegalStateException("Tenant não encontrado no contexto");
    }

    Tenant tenant = tenantRepo.findById(tenantId)
      .orElseThrow(() -> new RuntimeException("Tenant não encontrado"));

    Plan plan = planRepo.findById(request.planId())
      .orElseThrow(() -> new RuntimeException("Plano não encontrado"));

    // 1. Customer no Asaas (usa dados do AppUser)
    String customerId = asaasService.getOrCreateCustomer(currentUser);

    // 2. Criar assinatura no Asaas (sempre mensal)
    Map<String, Object> asaasSub = asaasService.createSubscription(customerId, plan, tenantId);

    Subscription sub = new Subscription();
    sub.setTenantId(tenant.getId());
    sub.setPlan(plan);
    sub.setStatus(Subscription.SubscriptionStatus.PENDING);
    sub.setStartDate(LocalDate.now());
    sub.setEndDate(null);  // perpétua
    sub.setAsaasSubscriptionId((String) asaasSub.get("id"));
    sub.setAsaasCustomerId(customerId);
    subscriptionRepo.save(sub);

    // 4. Primeira cobrança (link boleto / pix)
    Map<String, Object> firstPayment = (Map<String, Object>) asaasSub.get("firstPayment");
    String paymentId = (String) firstPayment.get("id");
    Map<String, Object> paymentDetails = asaasService.getPaymentDetails(paymentId);

    return new CheckoutResponseDTO(
      sub.getId(),
      sub.getStatus().name(),
      (String) paymentDetails.get("bankSlipUrl"),
      (String) paymentDetails.get("pixUrl")
    );
  }

  public SubscriptionStatusDTO getSubscriptionStatus() {
    String tenantIdStr = TenantContext.getTenantId();
    if (tenantIdStr == null) {
      return new SubscriptionStatusDTO(false, null, "Tenant não identificado");
    }
    UUID tenantId = UUID.fromString(tenantIdStr);

    Subscription sub = subscriptionRepo
      .findFirstByTenantIdAndStatusNotOrderByStartDateDesc(
        tenantId, Subscription.SubscriptionStatus.CANCELLED)
      .orElse(null);

    if (sub == null || sub.getStatus() == Subscription.SubscriptionStatus.PENDING) {
      return new SubscriptionStatusDTO(false, null, "Nenhuma assinatura ativa");
    }

    boolean active = sub.getStatus() == Subscription.SubscriptionStatus.ACTIVE;
    String message = active
      ? "Sua assinatura está ativa"
      : "Assinatura suspensa por falta de pagamento";

    return new SubscriptionStatusDTO(active, sub.getPlan().getName(), message);
  }
}