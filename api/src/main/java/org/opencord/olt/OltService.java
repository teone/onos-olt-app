package org.opencord.olt;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import java.util.List;

public interface OltService {
    /**
     * Provisions connectivity for a subscriber on an access device.
     * Installs flows for all uni tag information
     *
     * @param cp subscriber's connection point
     * @return true if successful false otherwise
     */
    boolean provisionSubscriber(ConnectPoint cp);
    boolean removeSubscriber(ConnectPoint cp);

     List<DeviceId> fetchOlts();
}
