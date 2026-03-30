package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AppRole;
import com.bhawana.lms.domain.RoleCode;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppRoleRepository extends JpaRepository<AppRole, UUID> {

    boolean existsByCode(RoleCode code);

    List<AppRole> findByCodeIn(Collection<RoleCode> codes);
}
