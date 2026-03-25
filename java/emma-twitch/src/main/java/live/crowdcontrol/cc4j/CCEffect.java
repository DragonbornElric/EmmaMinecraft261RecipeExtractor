package live.crowdcontrol.cc4j;

import live.crowdcontrol.cc4j.websocket.payload.PublicEffectPayload;

/**
 * Stub interface for cc4j effect. In the original CC, effects implement this.
 * Our Command interface extends this.
 */
public interface CCEffect {
	void onTrigger(PublicEffectPayload request, CCPlayer ccPlayer);
}
