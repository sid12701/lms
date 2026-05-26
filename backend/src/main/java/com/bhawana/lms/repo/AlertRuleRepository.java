package com.bhawana.lms.repo;

import com.bhawana.lms.domain.AlertRule;
import com.bhawana.lms.domain.AlertRuleTriggerKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AlertRuleRepository extends JpaRepository<AlertRule, UUID> {

    Optional<AlertRule> findByCode(String code);

    List<AlertRule> findByEnabledTrueAndTriggerKindOrderByCodeAsc(AlertRuleTriggerKind triggerKind);

    List<AlertRule> findAllByOrderByCodeAsc();
}
