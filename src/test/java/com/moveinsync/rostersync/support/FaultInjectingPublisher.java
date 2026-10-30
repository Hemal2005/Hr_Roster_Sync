package com.moveinsync.rostersync.support;

import com.moveinsync.rostersync.application.ChangeEventPublisher;
import com.moveinsync.rostersync.application.OutboxMessage;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for a process that dies at a chosen moment of a publish. Throwing out of the
 * relay leaves the outbox row exactly as a crash would: committed and not marked published.
 */
public final class FaultInjectingPublisher implements ChangeEventPublisher {

	public enum CrashPoint {
		/** The process dies before the broker receives anything. */
		BEFORE_SEND,
		/** The broker has the event, but the process dies before the row is marked published. */
		AFTER_SEND
	}

	private final ChangeEventPublisher real;
	private final CrashPoint crashPoint;
	private final AtomicInteger crashes = new AtomicInteger();

	public FaultInjectingPublisher(ChangeEventPublisher real, CrashPoint crashPoint) {
		this.real = real;
		this.crashPoint = crashPoint;
	}

	@Override
	public void publish(OutboxMessage message) {
		if (crashPoint == CrashPoint.AFTER_SEND) {
			real.publish(message);
		}
		crashes.incrementAndGet();
		throw new IllegalStateException("simulated crash " + crashPoint);
	}

	public int crashes() {
		return crashes.get();
	}
}
