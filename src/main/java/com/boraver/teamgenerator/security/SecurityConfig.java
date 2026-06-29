package com.boraver.teamgenerator.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public JwtService jwtService(AppSecurityProps props) {
    return new JwtService(props.jwtSecret(), props.jwtExpirationMs());
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService) throws Exception {
    return http
      .csrf(AbstractHttpConfigurer::disable)
      .cors(Customizer.withDefaults())
      .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        .requestMatchers(
          "/auth/**",
          "/v3/api-docs/**",
          "/swagger-ui/**",
          "/swagger-ui.html",
          "/uploads/**",
          "/webhooks/**"
        ).permitAll()
        .requestMatchers(HttpMethod.GET, "/championships/*/stream").permitAll()
        .anyRequest().authenticated()
      )
      .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
      .build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    var config = new org.springframework.web.cors.CorsConfiguration();
    config.setAllowedOrigins(java.util.List.of(
            // Produção
            "https://teamrandomizer-one.vercel.app",
            "https://rando.esp.br",
            "https://www.rando.esp.br",
            "http://rando.esp.br",
            "http://www.rando.esp.br",
            // VPS
            "http://23.106.44.62:3000",
            // Dev local
            "http://localhost:5173",
            "http://localhost:3000",
            "http://localhost:8081",       // ← Expo Web
            // Rede local (celular)
            "http://192.168.0.26:8081",    // ← Expo no seu IP atual
            "exp://192.168.0.26:8081"      // ← Expo Go
    ));
    config.setAllowedMethods(java.util.List.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
    ));
    config.setAllowedHeaders(java.util.List.of(
            "Authorization", "Content-Type", "Accept", "Origin"
    ));
    config.setAllowCredentials(false);

    var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}