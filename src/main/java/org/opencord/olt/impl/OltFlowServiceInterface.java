package org.opencord.olt.impl;

import com.google.common.collect.ImmutableMap;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.opencord.sadis.UniTagInformation;

import java.util.Map;
import java.util.Set;

public interface OltFlowServiceInterface {

    void handleBasicPortFlows(
            DiscoveredSubscriber sub, String defaultBpId, String oltBandwidthProfile)
                throws Exception;

    void handleSubscriberFlows(DiscoveredSubscriber sub, String defaultBpId) throws Exception;
    void handleNniFlows(Device device, Port port, OltFlowService.FlowAction action);

    boolean hasDefaultEapol(DeviceId deviceId, PortNumber portNumber);
    boolean hasDhcpFlows(DeviceId deviceId, PortNumber portNumber);
    boolean hasSubscriberFlows(DeviceId deviceId, PortNumber portNumber);

    void purgeDeviceFlows(DeviceId deviceId);

    Map<ConnectPoint, OltFlowService.OltPortStatus> getConnectPointStatus();
    ImmutableMap<ConnectPoint, Set<UniTagInformation>> getProgrammedSusbcribers();
}
