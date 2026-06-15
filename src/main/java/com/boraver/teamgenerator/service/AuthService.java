package com.boraver.teamgenerator.service;

import com.boraver.teamgenerator.entity.*;
import com.boraver.teamgenerator.repository.*;
import com.boraver.teamgenerator.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AuthService {

  private final TenantRepository tenantRepo;
  private final AppUserRepository userRepo;
  private final PasswordEncoder encoder;
  private final JwtService jwt;
  private final PlanRepository planRepository;
  private final SubscriptionRepository subscriptionRepository;
  private final EmailService emailService;

  public AuthService(TenantRepository tenantRepo,
                     AppUserRepository userRepo,
                     PasswordEncoder encoder,
                     JwtService jwt,
                     PlanRepository planRepository,
                     SubscriptionRepository subscriptionRepository,
                     EmailService emailService) {
    this.tenantRepo = tenantRepo;
    this.userRepo = userRepo;
    this.encoder = encoder;
    this.jwt = jwt;
    this.planRepository = planRepository;
    this.subscriptionRepository = subscriptionRepository;
    this.emailService = emailService;
  }

  @Transactional
  public LoginResult registerTenantWithAdmin(
          String tenantName,
          String tenantSlug,
          String adminName,
          String email,
          String password) {

    String slug = normalizeSlug(tenantSlug);
    String normEmail = email.trim().toLowerCase();

    if (tenantRepo.existsBySlug(slug)) {
      throw new IllegalArgumentException("Já existe um grupo com esse slug: " + slug);
    }

    // 1. Cria o Tenant
    Tenant t = new Tenant();
    t.setName(tenantName.trim());
    t.setSlug(slug);
    t = tenantRepo.save(t);

    // 2. Cria o usuário administrador (não verificado)
    AppUser u = new AppUser();
    u.setTenantId(t.getId());
    u.setName(adminName.trim());
    u.setEmail(normEmail);
    u.setPasswordHash(encoder.encode(password));
    u.setRole("ADMIN");
    u.setActive(true);
    u.setEmailVerified(false);

    // Gera token de verificação de e‑mail
    String emailVerificationToken = UUID.randomUUID().toString();
    u.setEmailVerificationToken(emailVerificationToken);
    u.setEmailVerificationTokenExpiry(LocalDateTime.now().plusHours(24));

    userRepo.save(u);

    // 3. Atribui assinatura do plano Free automaticamente
    Plan freePlan = planRepository.findByName("Free")
            .orElseThrow(() -> new IllegalStateException("Plano Free não encontrado"));
    Subscription sub = new Subscription();
    sub.setTenant(t);
    sub.setPlan(freePlan);
    sub.setStatus(Subscription.SubscriptionStatus.ACTIVE);
    sub.setStartDate(LocalDate.now());
    subscriptionRepository.save(sub);

    // 4. Envia e‑mail de verificação (assíncrono ou síncrono)
    try {
      emailService.sendVerificationEmail(normEmail, emailVerificationToken);
    } catch (Exception e) {
      // Loga o erro, mas não impede o cadastro
      System.err.println("Erro ao enviar e‑mail de verificação: " + e.getMessage());
    }

    // 5. Gera token JWT (pode ou não permitir login antes da verificação)
    String token = jwt.generateToken(
            u.getId().toString(),
            u.getTenantId().toString(),
            u.getRole(),
            u.getEmail(),
            u.getName());

    return buildLoginResult(token, u, t, freePlan);
  }

  public LoginResult login(UUID tenantId, String email, String password) {
    AppUser user = userRepo.findByTenantIdAndEmail(tenantId, email)
            .orElseThrow(() -> new SecurityException("Invalid credentials"));

    if (!user.isActive() || !encoder.matches(password, user.getPasswordHash())) {
      throw new SecurityException("Invalid credentials");
    }

    // Opcional: bloquear login se e‑mail não verificado
    // if (!user.isEmailVerified()) {
    //     throw new SecurityException("E-mail não verificado. Verifique sua caixa de entrada.");
    // }

    String token = jwt.generateToken(
            user.getId().toString(),
            user.getTenantId().toString(),
            user.getRole(),
            user.getEmail(),
            user.getName());

    Tenant tenant = tenantRepo.findById(tenantId)
            .orElseThrow(() -> new IllegalStateException("Tenant não encontrado"));

    Plan plan = subscriptionRepository.findByTenantIdAndStatus(tenantId, Subscription.SubscriptionStatus.ACTIVE)
            .map(Subscription::getPlan)
            .orElse(null);

    return buildLoginResult(token, user, tenant, plan);
  }

  public LoginResult loginBySlug(String tenantSlug, String email, String password) {
    var slug = tenantSlug.trim().toLowerCase();
    var tenant = tenantRepo.findBySlug(slug)
            .orElseThrow(() -> new IllegalArgumentException("Grupo não encontrado"));

    return login(tenant.getId(), email, password);
  }

  @Transactional
  public void verifyEmailAndSetPassword(String token, String password) {
    AppUser user = userRepo.findByEmailVerificationToken(token)
            .orElseThrow(() -> new IllegalArgumentException("Token de verificação inválido"));

    if (user.getEmailVerificationTokenExpiry() != null &&
            user.getEmailVerificationTokenExpiry().isBefore(LocalDateTime.now())) {
      throw new IllegalArgumentException("Token de verificação expirado");
    }

    if (password == null || password.length() < 6) {
      throw new IllegalArgumentException("Senha deve ter no mínimo 6 caracteres");
    }

    user.setEmailVerified(true);
    user.setPasswordHash(encoder.encode(password));
    user.setEmailVerificationToken(null);
    user.setEmailVerificationTokenExpiry(null);
    userRepo.save(user);

    // Envia e-mail de boas-vindas (opcional)
    try {
      emailService.sendWelcomeEmail(user.getEmail(), user.getName());
    } catch (Exception e) {
      System.err.println("Erro ao enviar e-mail de boas-vindas: " + e.getMessage());
    }
  }

  // ==================== Métodos auxiliares ====================

  private LoginResult buildLoginResult(String token, AppUser user, Tenant tenant, Plan plan) {
    return new LoginResult(
            token,
            user.getTenantId(),
            user.getId(),
            user.getRole(),
            user.getName(),
            tenant.getLogoUrl(),
            tenant.getPrimaryColor(),
            tenant.getSecondaryColor(),
            plan != null ? plan.getName() : "Free",
            plan != null ? plan.getFeatureList() : java.util.Collections.emptyList(),
            user.isEmailVerified(),
            tenant.getName()
    );
  }

  private static String normalizeSlug(String raw) {
    String s = raw == null ? "" : raw.trim().toLowerCase();
    s = s.replaceAll("[^a-z0-9-]", "-");
    s = s.replaceAll("-{2,}", "-");
    s = s.replaceAll("(^-|-$)", "");
    if (s.length() < 3) throw new IllegalArgumentException("tenantSlug too short");
    return s;
  }

  // ==================== Records ====================

  public record LoginResult(
          String token,
          UUID tenantId,
          UUID userId,
          String role,
          String userName,
          String logoUrl,
          String primaryColor,
          String secondaryColor,
          String planName,
          java.util.List<String> features,
          boolean emailVerified,
          String groupName
  ) {}
}