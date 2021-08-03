package org.opencord.olt;

import org.onosproject.net.Device;
import org.onosproject.net.Port;

public class DiscoveredSubscriber {

    public enum Status {
        ADDED,
        REMOVED,
    }

    public Port port;
    public Device device;
    public Enum<Status> status;
    public boolean provisionSubscriber;

    DiscoveredSubscriber(Device device, Port port, Status status, boolean provisionSubscriber) {
        this.device = device;
        this.port = port;
        this.status = status;
        this.provisionSubscriber = provisionSubscriber;
    }

    @Override
    public String toString() {
        return String.format("%s{device:%s, port: %s, status: %s, provisionSubscriber: %s}",
                this.getClass().getName(),
                this.device.id().toString(),
                this.port.number().toString(),
                this.status.toString(),
                this.provisionSubscriber
        );
    }
}
