package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.dto.subscription.CheckoutResponseDTO;
import com.boraver.teamgenerator.dto.subscription.SubscribeRequestDTO;
import com.boraver.teamgenerator.dto.subscription.SubscriptionStatusDTO;
import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.AppUserRepository;
import com.boraver.teamgenerator.repository.PlanRepository;
import com.boraver.teamgenerator.repository.SubscriptionRepository;
import com.boraver.teamgenerator.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SubscriptionService {

  private final SubscriptionRepository subscriptionRepo;
  private final PlanRepository planRepo;
  private final TenantRepository tenantRepo;
  private final AppUserRepository appUserRepository;
  private final AsaasService asaasService;

  public SubscriptionService(SubscriptionRepository subscriptionRepo,
                             PlanRepository planRepo,
                             TenantRepository tenantRepo,
                             AppUserRepository appUserRepository,
                             AsaasService asaasService) {
    this.subscriptionRepo = subscriptionRepo;
    this.planRepo = planRepo;
    this.tenantRepo = tenantRepo;
    this.appUserRepository = appUserRepository;
    this.asaasService = asaasService;
  }

  @Transactional
  public CheckoutResponseDTO subscribe(SubscribeRequestDTO request) {
    UUID tenantId = getTenantId();
    Tenant tenant = tenantRepo.findById(tenantId)
      .orElseThrow(() -> new RuntimeException("Tenant não encontrado"));
    Plan plan = planRepo.findById(request.planId())
      .orElseThrow(() -> new RuntimeException("Plano não encontrado"));
    AppUser adminUser = appUserRepository.findFirstByTenantIdAndRole(tenantId, "ADMIN")
      .orElseThrow(() -> new RuntimeException("Admin não encontrado"));

    // Verifica/cancela assinatura existente (omitido para brevidade, mas mantenha o seu código)

    String customerId = asaasService.getOrCreateCustomer(adminUser);
    Map<String, Object> asaasSub = asaasService.createSubscription(customerId, plan, tenantId);

    // Log para debug
    System.out.println("Resposta da criação de assinatura Asaas: " + asaasSub);

    // Salva a assinatura local primeiro, mas precisamos do paymentId
    Subscription sub = new Subscription();
    sub.setTenant(tenant);
    sub.setPlan(plan);
    sub.setStatus(Subscription.SubscriptionStatus.PENDING);
    sub.setStartDate(LocalDate.now());
    sub.setEndDate(null);
    sub.setAsaasSubscriptionId((String) asaasSub.get("id"));
    sub.setAsaasCustomerId(customerId);
    subscriptionRepo.save(sub);

    // ─── Obtém detalhes da primeira cobrança ───
    String paymentId = null;
    String bankSlipUrl = null;
    String pixUrl = null;

    Object firstPaymentObj = asaasSub.get("firstPayment");
    if (firstPaymentObj instanceof Map) {
      Map<String, Object> firstPayment = (Map<String, Object>) firstPaymentObj;
      paymentId = (String) firstPayment.get("id");
    } else if (asaasSub.get("firstPaymentId") != null) {
      // Caso venha apenas o ID (comportamento antigo ou diferente)
      paymentId = asaasSub.get("firstPaymentId").toString();
    }

    if (paymentId == null) {
      // Fallback: busca os pagamentos da assinatura e pega o primeiro
      Map<String, Object> paymentsData = asaasService.getPaymentsBySubscription((String) asaasSub.get("id"));
      List<Map<String, Object>> paymentList = (List<Map<String, Object>>) paymentsData.get("data");
      if (paymentList != null && !paymentList.isEmpty()) {
        paymentId = (String) paymentList.get(0).get("id");
      } else {
        throw new RuntimeException("Nenhum pagamento encontrado para a assinatura");
      }
    }

    // Com o paymentId, busca detalhes completos
    Map<String, Object> paymentDetails = asaasService.getPaymentDetails(paymentId);
    bankSlipUrl = (String) paymentDetails.get("bankSlipUrl");
    pixUrl = (String) paymentDetails.get("pixUrl");

    return new CheckoutResponseDTO(
      sub.getId(),
      sub.getStatus().name(),
      bankSlipUrl,
      pixUrl
    );
  }

  @Transactional
  public CheckoutResponseDTO downgrade(SubscribeRequestDTO request) {
    UUID tenantId = getTenantId();
    Plan newPlan = planRepo.findById(request.planId())
            .orElseThrow(() -> new RuntimeException("Plano não encontrado"));

    // Busca assinatura ativa atual
    Subscription currentSub = subscriptionRepo
            .findByTenantIdAndStatus(tenantId, Subscription.SubscriptionStatus.ACTIVE)
            .orElseThrow(() -> new RuntimeException("Nenhuma assinatura ativa encontrada"));

    // Verifica se é realmente downgrade (preço menor)
    if (newPlan.getPrice().compareTo(currentSub.getPlan().getPrice()) >= 0) {
      throw new RuntimeException("Use o endpoint de upgrade para mudar para um plano superior");
    }

    // Cancela assinatura atual no Asaas
    asaasService.cancelSubscription(currentSub.getAsaasSubscriptionId());
    currentSub.setStatus(Subscription.SubscriptionStatus.CANCELLED);
    subscriptionRepo.save(currentSub);

    // Cria nova assinatura com o plano inferior (já ativa, sem cobrança imediata)
    String customerId = currentSub.getAsaasCustomerId();
    Map<String, Object> asaasSub = asaasService.createSubscription(customerId, newPlan, tenantId);

    Subscription newSub = new Subscription();
    newSub.setTenant(currentSub.getTenant());   // mesmo tenant
    newSub.setPlan(newPlan);
    newSub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
    newSub.setStartDate(LocalDate.now());
    newSub.setEndDate(null);
    newSub.setAsaasSubscriptionId((String) asaasSub.get("id"));
    newSub.setAsaasCustomerId(customerId);
    subscriptionRepo.save(newSub);

    return new CheckoutResponseDTO(
            newSub.getId(),
            newSub.getStatus().name(),
            null,
            null
    );
  }

  public SubscriptionStatusDTO getSubscriptionStatus() {
    UUID tenantId = getTenantId();

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

  private UUID getTenantId() {
    String tenantIdStr = TenantContext.getTenantId();
    if (tenantIdStr == null) {
      throw new IllegalStateException("Tenant não encontrado no contexto");
    }
    return UUID.fromString(tenantIdStr);
  }
}