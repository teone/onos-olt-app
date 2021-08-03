package org.opencord.olt.impl;

import org.onosproject.net.Device;
import org.onosproject.net.Port;
import org.opencord.sadis.SadisService;

public interface OltDeviceServiceInterface {
    boolean isOlt(Device device);
    boolean isNniPort(Device device, Port port);
    void bindSadisService(SadisService service);
    void unbindSadisService();
}
