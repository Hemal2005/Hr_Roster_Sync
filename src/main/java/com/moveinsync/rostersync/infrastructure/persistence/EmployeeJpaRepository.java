package com.moveinsync.rostersync.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data generates the implementation from the method names.
 */
interface EmployeeJpaRepository extends JpaRepository<EmployeeEntity, EmployeeKey> {

	Optional<EmployeeEntity> findByTenantIdAndEmployeeId(String tenantId, String employeeId);

	long countByTenantIdAndStatus(String tenantId, String status);
}
