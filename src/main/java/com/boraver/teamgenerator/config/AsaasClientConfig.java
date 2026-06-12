package com.boraver.teamgenerator.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AsaasClientConfig {

  @Value("${asaas.api-key}")
  private String apiKey;

  @Value("${asaas.environment}")
  private String environment;

  @Bean
  public RestTemplate asaasRestTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.getInterceptors().add((request, body, execution) -> {
      request.getHeaders().set("access_token", apiKey);
      request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
      return execution.execute(request, body);
    });
    return restTemplate;
  }

  public String getBaseUrl() {
    return "sandbox".equals(environment)
      ? "https://api-sandbox.asaas.com/v3"
      : "https://api.asaas.com/v3";
  }
}