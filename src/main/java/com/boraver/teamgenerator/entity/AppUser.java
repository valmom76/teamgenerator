package com.boraver.teamgenerator.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
    name = "app_user",
    uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "email"})
)
@Getter
@Setter
public class AppUser {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(length = 36)
  private UUID id;

  @Column(name = "tenant_id", nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  private UUID tenantId;

  @Column(nullable=false, length=120)
  private String name;

  @Column(nullable=false, length=180)
  private String email;

  @Column(name = "cpf_cnpj")
  private String cpfCnpj;

  @Column(name="password_hash", nullable=false, length=255)
  private String passwordHash;

  @Column(nullable=false, length=30)
  private String role = "ADMIN";

  @Column(nullable=false)
  private boolean active = true;

  @Column(name="created_at", nullable=false)
  private OffsetDateTime createdAt = OffsetDateTime.now();

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified = false;

  @Column(name = "email_verification_token", length = 36)
  private String emailVerificationToken;

  @Column(name = "email_verification_token_expiry")
  private LocalDateTime emailVerificationTokenExpiry;
}