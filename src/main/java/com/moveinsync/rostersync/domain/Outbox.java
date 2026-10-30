package com.moveinsync.rostersync.domain;

/**
 * Change events waiting to be published. {@link #enqueue} must run in the same
 * transaction as the change it describes.
 */
public interface Outbox {

	void enqueue(ChangeEvent event);
}
