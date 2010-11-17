package org.pcgod.mumbleclient.service.model;

/**
 * Represents possible states of talking.
 * @author xZise
 */
public enum TalkingState {
    /** Not talking */
	PASSIVE,
	/** User is muted */
	MUTED,
	/** User is deafened (therefore also mute) */
	DEAFENED,
}