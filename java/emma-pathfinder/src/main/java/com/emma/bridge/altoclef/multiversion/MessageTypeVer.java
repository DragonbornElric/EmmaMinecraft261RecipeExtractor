package adris.altoclef.multiversion;

import net.minecraft.network.message.MessageType;

public class MessageTypeVer {

    public static MessageType getMessageType(MessageType.Parameters parameters) {

        return parameters.type().value();
    }
}
