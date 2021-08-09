package org.opencord.olt.impl;

import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

public interface OltFlowServiceInterface {

    void handleBasicPortFlows(
            DiscoveredSubscriber sub, String defaultMeterId)
                throws Exception;

    void handleSubscriberFlows(DiscoveredSubscriber sub) throws Exception;

    boolean hasDefaultEapol(DeviceId deviceId, PortNumber portNumber);
}
