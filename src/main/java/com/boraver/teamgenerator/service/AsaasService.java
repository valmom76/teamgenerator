package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.config.AsaasClientConfig;
import com.boraver.teamgenerator.entity.AppUser;
import com.boraver.teamgenerator.entity.Plan;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
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

  public String getOrCreateCustomer(AppUser adminUser) {
    String email = adminUser.getEmail();
    String url = config.getBaseUrl() + "/customers?email=" + email;

    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
      url, HttpMethod.GET, null, MAP_TYPE_REF);
    List<Map<String, Object>> data = getDataList(resp.getBody());
    if (!data.isEmpty()) {
      return extractString(data.get(0), "id");
    }

    Map<String, Object> body = new HashMap<>();
    body.put("name", adminUser.getName());
    body.put("email", email);

    ResponseEntity<Map<String, Object>> postResp = asaasRestTemplate.exchange(
      config.getBaseUrl() + "/customers",
      HttpMethod.POST,
      new HttpEntity<>(body),
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

    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
      config.getBaseUrl() + "/subscriptions",
      HttpMethod.POST,
      new HttpEntity<>(body),
      MAP_TYPE_REF);
    return resp.getBody();
  }

  public Map<String, Object> getPaymentDetails(String paymentId) {
    ResponseEntity<Map<String, Object>> resp = asaasRestTemplate.exchange(
      config.getBaseUrl() + "/payments/" + paymentId,
      HttpMethod.GET,
      null,
      MAP_TYPE_REF);
    return resp.getBody();
  }

  // ─── Métodos auxiliares ───

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
}