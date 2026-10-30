package com.moveinsync.rostersync.domain.reconciliation;

import com.moveinsync.rostersync.domain.EmployeeStatus;
import java.util.Objects;

/**
 * One export row as the diff sees it. An invalid row still proves the employee is in
 * the file: it is never applied, and its employee is never deactivated because of it.
 *
 * @param version and {@code hashHex} are meaningful only when {@code valid}
 */
public record FileEntry(String employeeId, long version, String hashHex, EmployeeStatus status, boolean valid) {

	public FileEntry {
		Objects.requireNonNull(employeeId, "employeeId");
		if (valid) {
			Objects.requireNonNull(hashHex, "hashHex");
			Objects.requireNonNull(status, "status");
		}
	}

	public static FileEntry invalid(String employeeId) {
		return new FileEntry(employeeId, 0, null, null, false);
	}
}
