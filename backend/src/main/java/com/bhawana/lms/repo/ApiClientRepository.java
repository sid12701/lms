package com.bhawana.lms.repo;

import com.bhawana.lms.domain.ApiClient;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientRepository extends JpaRepository<ApiClient, UUID> {

    boolean existsByClientIdIgnoreCase(String clientId);
}
