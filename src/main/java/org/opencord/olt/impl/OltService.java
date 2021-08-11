package org.opencord.olt.impl;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;

import java.util.List;

public interface OltService {
    /**
     * Provisions connectivity for a subscriber on an access device.
     * Installs flows for all uni tag information
     *
     * @param port subscriber's connection point
     * @return true if successful false otherwise
     */
    boolean provisionSubscriber(ConnectPoint port);

     List<DeviceId> fetchOlts();
}
