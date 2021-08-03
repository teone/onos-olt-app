package org.opencord.olt;

import org.onosproject.net.Device;
import org.onosproject.net.Port;

public interface OltDeviceInterface {
    boolean isOlt(Device device);
    boolean isNniPort(Device device, Port port);
}
