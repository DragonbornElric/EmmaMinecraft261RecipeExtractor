package live.crowdcontrol.cc4j;

import live.crowdcontrol.cc4j.websocket.payload.PublicEffectPayload;

/**
 * Interface for timed-duration effects that have start/end/pause/resume lifecycle.
 */
public interface CCTimedEffect extends CCEffect {
	default void onEnd(PublicEffectPayload request, CCPlayer source) {}
	default void onPause(PublicEffectPayload request, CCPlayer source) {}
	default void onResume(PublicEffectPayload request, CCPlayer source) {}
}
