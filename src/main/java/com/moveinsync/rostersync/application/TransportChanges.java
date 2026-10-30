package com.moveinsync.rostersync.application;

import com.moveinsync.rostersync.domain.TransportOwnedFields;

/**
 * A partial edit of the transport-owned fields: a field that was not sent keeps its value,
 * a field sent as null is cleared.
 */
public record TransportChanges(boolean pickupPointSent, String pickupPoint, boolean specialAssistanceSent,
		String specialAssistance) {

	TransportOwnedFields applyTo(TransportOwnedFields current) {
		return new TransportOwnedFields(
				pickupPointSent ? pickupPoint : current.pickupPoint(),
				specialAssistanceSent ? specialAssistance : current.specialAssistance());
	}

	@Override
	public String toString() {
		return "TransportChanges[pickupPointSent=" + pickupPointSent + ", specialAssistanceSent="
				+ specialAssistanceSent + "]";
	}
}
