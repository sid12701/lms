package com.bhawana.lms.repo;

import com.bhawana.lms.domain.Lsp;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LspRepository extends JpaRepository<Lsp, UUID> {

    boolean existsByCodeIgnoreCase(String code);
}
