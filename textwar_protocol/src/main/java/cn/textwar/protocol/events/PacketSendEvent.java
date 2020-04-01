package cn.textwar.protocol.events;

import cn.textwar.events.Event;
import cn.textwar.protocol.TextWarProtocol;

public class PacketSendEvent extends Event {

    private TextWarProtocol protocol;

    public PacketSendEvent(TextWarProtocol protocol) {
        super("packet_send");
        this.protocol = protocol;
    }

    public TextWarProtocol getProtocol() {
        return protocol;
    }
}
