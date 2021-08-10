package org.opencord.olt.impl;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;

import java.util.Map;

public interface OltFlowServiceInterface {

    void handleBasicPortFlows(
            DiscoveredSubscriber sub, String defaultMeterId)
                throws Exception;

    void handleSubscriberFlows(DiscoveredSubscriber sub) throws Exception;

    void handleNniFlows(Device device, Port port, OltFlowService.FlowAction action);

    boolean hasDefaultEapol(DeviceId deviceId, PortNumber portNumber);

    void purgeDeviceFlows(DeviceId deviceId);

    Map<ConnectPoint, OltFlowService.OltPortStatus> getConnectPointStatus();
}
