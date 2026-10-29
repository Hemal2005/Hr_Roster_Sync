package com.moveinsync.rostersync.domain;

public enum FieldOwner {
	/** The client's HR system, writing through the HR event path. */
	HR,
	/** MoveInSync's transport admin, writing through the transport endpoint. */
	TRANSPORT
}
