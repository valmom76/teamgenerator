package com.boraver.teamgenerator.repository;

import com.boraver.teamgenerator.entity.*;
import org.springframework.data.jpa.repository.*;
import java.util.*;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
  Optional<AppUser> findByTenantIdAndEmail(UUID tenantId, String email);
  Optional<AppUser> findByEmailVerificationToken(String token);
  Optional<AppUser> findFirstByTenantIdAndRole(UUID tenantId, String role);
}
