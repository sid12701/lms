package com.bhawana.lms.repo;

import com.bhawana.lms.domain.Lsp;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LspRepository extends JpaRepository<Lsp, UUID> {

    List<Lsp> findAllByOrderByCodeAsc();

    List<Lsp> findAllByOrderByNameAsc();

    boolean existsByCodeIgnoreCase(String code);

    java.util.Optional<Lsp> findByCodeIgnoreCase(String code);
}
