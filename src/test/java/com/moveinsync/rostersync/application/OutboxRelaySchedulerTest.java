package com.moveinsync.rostersync.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class OutboxRelaySchedulerTest {

	private static final OutboxRelay.RelayResult FAILED = new OutboxRelay.RelayResult(0, true, false);
	private static final OutboxRelay.RelayResult EMPTY = new OutboxRelay.RelayResult(0, false, false);

	@Test
	void backsOffAfterAFailureInsteadOfRetryingEveryTick() {
		OutboxRelay relay = mock(OutboxRelay.class);
		when(relay.relayOnce()).thenReturn(FAILED);
		var scheduler = new OutboxRelayScheduler(relay, 100, 10_000);

		scheduler.tick();
		scheduler.tick();
		scheduler.tick();

		verify(relay, times(1)).relayOnce();
	}

	@Test
	void drainsFullBatchesImmediately() {
		OutboxRelay relay = mock(OutboxRelay.class);
		when(relay.relayOnce())
				.thenReturn(new OutboxRelay.RelayResult(100, false, false))
				.thenReturn(new OutboxRelay.RelayResult(100, false, false))
				.thenReturn(new OutboxRelay.RelayResult(7, false, false));
		var scheduler = new OutboxRelayScheduler(relay, 100, 10_000);

		scheduler.tick();

		verify(relay, times(3)).relayOnce();
	}

	@Test
	void keepsPollingWhenThereIsNothingToSend() {
		OutboxRelay relay = mock(OutboxRelay.class);
		when(relay.relayOnce()).thenReturn(EMPTY);
		var scheduler = new OutboxRelayScheduler(relay, 100, 10_000);

		scheduler.tick();
		scheduler.tick();

		verify(relay, times(2)).relayOnce();
	}
}
