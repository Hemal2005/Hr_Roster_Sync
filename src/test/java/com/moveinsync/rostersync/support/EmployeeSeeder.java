package com.moveinsync.rostersync.support;

import com.moveinsync.rostersync.application.ReconciliationSource;
import com.moveinsync.rostersync.application.reconciliation.ExportReader;
import com.moveinsync.rostersync.domain.EmployeeId;
import com.moveinsync.rostersync.domain.ErrorCode;
import com.moveinsync.rostersync.domain.PayloadHasher;
import com.moveinsync.rostersync.domain.TenantId;
import com.moveinsync.rostersync.domain.reconciliation.BucketAssigner;
import com.moveinsync.rostersync.domain.validation.IncomingHrRecord;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import javax.sql.DataSource;
import org.postgresql.PGConnection;

/**
 * Loads many employees fast with COPY instead of one apply per row. Each row of an export
 * becomes an active employee at version 1, hashed the way reconciliation hashes export
 * rows, so the same row in a later export is unchanged.
 */
public final class EmployeeSeeder {

	private final DataSource dataSource;
	private final BucketAssigner buckets;

	public EmployeeSeeder(DataSource dataSource, BucketAssigner buckets) {
		this.dataSource = dataSource;
		this.buckets = buckets;
	}

	/** Seeds every row of {@code export}; {@code work} is a scratch file for the COPY input. */
	public void seedFrom(TenantId tenantId, Path export, Path work, Instant lastHrEventAt) {
		PayloadHasher hasher = new PayloadHasher();
		try (BufferedWriter out = Files.newBufferedWriter(work, StandardCharsets.UTF_8)) {
			ExportReader.read(export, new ExportReader.Handler() {
				@Override
				public void row(long lineNo, IncomingHrRecord record) {
					String hash = hasher.hash(record.toEvent(tenantId, ReconciliationSource.NAME)).hex();
					String line = String.join(",", tenantId.value(), record.employeeId(), record.firstName(),
							record.lastName(), record.email(), record.phone(), record.managerId(), record.officeCode(),
							record.shiftCode(), "ACTIVE", "1", "\\x" + hash, lastHrEventAt.toString(),
							Integer.toString(buckets.bucketOf(new EmployeeId(record.employeeId()))));
					try {
						out.write(line);
						out.write('\n');
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}

				@Override
				public void malformed(long lineNo, String employeeId, ErrorCode code) {
					throw new AssertionError("seed row is malformed at line " + lineNo + ": " + code);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		try (Connection connection = dataSource.getConnection(); Reader in = Files.newBufferedReader(work)) {
			connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
					COPY employee (tenant_id, employee_id, first_name, last_name, email, phone, manager_id,
					               office_code, shift_code, status, hr_version, hr_payload_hash, last_hr_event_at, bucket)
					FROM STDIN WITH (FORMAT csv)
					""", in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} catch (SQLException e) {
			throw new IllegalStateException("COPY into employee failed: " + e.getSQLState(), e);
		}
	}
}
