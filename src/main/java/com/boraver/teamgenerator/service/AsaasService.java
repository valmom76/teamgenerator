package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.common.TenantContext;
import com.boraver.teamgenerator.config.AsaasClientConfig;
import com.boraver.teamgenerator.entity.AppUser;
import com.boraver.teamgenerator.entity.Plan;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.*;

@Service
public class AsaasService {

  private final RestTemplate asaasRestTemplate;
  private final AsaasClientConfig config;

  private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE_REF =
          new ParameterizedTypeReference<>() {};

  public AsaasService(RestTemplate asaasRestTemplate, AsaasClientConfig config) {
    this.asaasRestTemplate = asaasRestTemplate;
    this.config = config;
  }

  private HttpHeaders getHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set("access_token", config.getApiKey());
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  public String getOrCreateCustomer(AppUser adminUser) {
    String tenantId = TenantContext.getTenantId();
    String url = config.getBaseUrl() + "/customers?externalReference=" + tenantId;

    try {
      ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
              url, HttpMethod.GET, new HttpEntity<>(getHeaders()), MAP_TYPE_REF);
      List<Map<String, Object>> data = getDataList(resp.getBody());
      if (!data.isEmpty()) {
        return extractString(data.get(0), "id");
      }
    } catch (Exception e) {
      System.out.println("Cliente não encontrado, criando novo...");
    }

    // Cria novo cliente
    Map<String, Object> body = new HashMap<>();
    body.put("name", adminUser.getName());
    body.put("email", adminUser.getEmail());
    body.put("cpfCnpj", getCpfCnpj(adminUser));
    body.put("externalReference", tenantId);

    HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, getHeaders());

    ResponseEntity<Map<String, Object>> postResp = asaasRestTemplate.exchange(
            config.getBaseUrl() + "/customers",
            HttpMethod.POST,
            requestEntity,
            MAP_TYPE_REF);
    return extractString(postResp.getBody(), "id");
  }

  public Map<String, Object> createSubscription(String customerId, Plan plan, UUID tenantId) {
    Map<String, Object> body = new HashMap<>();
    body.put("customer", customerId);
    body.put("billingType", "UNDEFINED");
    body.put("cycle", "MONTHLY");
    body.put("value", plan.getPrice());
    body.put("nextDueDate", LocalDate.now().toString());
    body.put("description", "Plano " + plan.getName());
    body.put("externalReference", tenantId.toString());

    HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, getHeaders());

    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
            config.getBaseUrl() + "/subscriptions",
            HttpMethod.POST,
            requestEntity,
            MAP_TYPE_REF);
    return resp.getBody();
  }

  public Map<String, Object> getPaymentDetails(String paymentId) {
    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
            config.getBaseUrl() + "/payments/" + paymentId,
            HttpMethod.GET,
            new HttpEntity<>(getHeaders()),
            MAP_TYPE_REF);
    return resp.getBody();
  }

  public void cancelSubscription(String subscriptionId) {
    if (subscriptionId == null || subscriptionId.isBlank()) return;

    Map<String, Object> body = new HashMap<>();
    body.put("status", "INACTIVE");

    HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, getHeaders());

    asaasRestTemplate.exchange(
            config.getBaseUrl() + "/subscriptions/" + subscriptionId,
            HttpMethod.PUT,
            requestEntity,
            MAP_TYPE_REF);
  }

  public Map<String, Object> getPaymentsBySubscription(String subscriptionId) {
    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
      config.getBaseUrl() + "/subscriptions/" + subscriptionId + "/payments",
      HttpMethod.GET,
      new HttpEntity<>(getHeaders()),
      MAP_TYPE_REF);
    return resp.getBody();
  }

  // Métodos auxiliares
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> getDataList(Map<String, Object> body) {
    if (body == null) return Collections.emptyList();
    Object data = body.get("data");
    if (data instanceof List) {
      return (List<Map<String, Object>>) data;
    }
    return Collections.emptyList();
  }

  private String extractString(Map<String, Object> body, String key) {
    if (body == null) {
      throw new IllegalStateException("Resposta da API Asaas está nula");
    }
    Object value = body.get(key);
    if (value == null) {
      throw new IllegalStateException("Campo '" + key + "' não encontrado na resposta Asaas");
    }
    return value.toString();
  }

  private String getCpfCnpj(AppUser user) {
    if (user.getCpfCnpj() != null && !user.getCpfCnpj().isBlank()) {
      return user.getCpfCnpj();
    }
    // Fallback para sandbox
    if ("sandbox".equals(config.getEnvironment())) {
      return "24971563792"; // CPF de teste
    }
    throw new RuntimeException("CPF/CNPJ é obrigatório para assinatura");
  }
}