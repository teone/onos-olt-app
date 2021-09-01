package org.opencord.olt.impl;

import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.onlab.util.Tools.groupedThreads;

public class OltDeviceListener implements DeviceListener {
    private final Logger log = LoggerFactory.getLogger(getClass());

    protected ClusterService clusterService;

    protected MastershipService mastershipService;

    protected LeadershipService leadershipService;

    protected DeviceService deviceService;

    protected OltDeviceServiceInterface oltDeviceService;

    protected OltFlowServiceInterface oltFlowService;

    protected OltMeterServiceInterface oltMeterService;

    private BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue;

    protected ExecutorService portExecutor;

    public OltDeviceListener(ClusterService clusterService, MastershipService mastershipService,
                             LeadershipService leadershipService, DeviceService deviceService,
                             OltDeviceServiceInterface oltDeviceService, OltFlowServiceInterface oltFlowService,
                             OltMeterServiceInterface oltMeterService,
                             BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue) {
        this.clusterService = clusterService;
        this.mastershipService = mastershipService;
        this.leadershipService = leadershipService;
        this.deviceService = deviceService;
        this.oltDeviceService = oltDeviceService;
        this.oltFlowService = oltFlowService;
        this.oltMeterService = oltMeterService;
        this.discoveredSubscribersQueue = discoveredSubscribersQueue;
        this.portExecutor = Executors.newFixedThreadPool(8,
                groupedThreads("onos/olt-device-listener",
                        "olt-device-listener-%d"));
    }

    public void deactivate() {
        this.portExecutor.shutdown();
    }

    /**
     * Checks for mastership or falls back to leadership on deviceId.
     * If the device is available use mastership,
     * otherwise fallback on leadership.
     * Leadership on the device topic is needed because the master can be NONE
     * in case the device went away, we still need to handle events
     * consistently
     *
     * @param deviceId The device ID to check.
     * @return boolean (true if the current instance is managing the device)
     */
    protected boolean isLocalLeader(DeviceId deviceId) {
        // FIXME import OltDeviceService and use the method in there
        if (deviceService.isAvailable(deviceId)) {
            return mastershipService.isLocalMaster(deviceId);
        } else {
            // Fallback with Leadership service - device id is used as topic
            NodeId leader = leadershipService.runForLeadership(
                    deviceId.toString()).leaderNodeId();
            // Verify if this node is the leader
            return clusterService.getLocalNode().id().equals(leader);
        }
    }

    @Override
    public void event(DeviceEvent event) {
        // TODO handle events for existing items when app is installed/removed
        if (!oltDeviceService.isOlt(event.subject())) {
            // if the device is not an OLT recognized in org.opencord.sadis
            // then we don't care about the events it is emitting
            return;
        }
        switch (event.type()) {
            case PORT_STATS_UPDATED:
            case DEVICE_ADDED:
                return;
            case PORT_ADDED:
            case PORT_UPDATED:
            case PORT_REMOVED:
                if (!isLocalLeader(event.subject().id())) {
                    log.trace("Device {} is not local to this node", event.subject().id());
                    return;
                }
                // port added, updated and removed are treated in the same way as we only care whether the port
                // is enabled or not
                portExecutor.execute(() -> {
                    handleOltPort(event.type(), event.subject(), event.port());
                });
                return;
            case DEVICE_AVAILABILITY_CHANGED:
                // NOTE that upon disconnection there is no mastership on the device,
                // and we should anyway clear the local cache of the flows/meters across instances
                DeviceId deviceId = event.subject().id();
                if (!deviceService.isAvailable(deviceId) && deviceService.getPorts(deviceId).isEmpty()) {
                    // we're only clearing the device if there are no available ports,
                    // otherwise we assume it's a temporary disconnection
                    log.info("Device {} availability changed to false ports are empty, purging meters and flows",
                            deviceId);
                    oltFlowService.purgeDeviceFlows(deviceId);
                    oltMeterService.purgeDeviceMeters(deviceId);
                } else {
                    log.info("Device {} availability changed to false, but ports are still available, " +
                            "assuming disconnection", deviceId);
                }
                return;
            case DEVICE_REMOVED:
                log.info("Device Removed,  purging meters and flows");
                oltFlowService.purgeDeviceFlows(event.subject().id());
                oltMeterService.purgeDeviceMeters(event.subject().id());
                return;
            default:
                log.debug("OltDeviceListener receives event: {}", event);
        }
    }

    protected void handleOltPort(DeviceEvent.Type type, Device device, Port port) {
        log.info("OltDeviceListener receives event {} for port {} with status {} on device {}", type, port.number(),
                port.isEnabled() ? "ENABLED" : "DISABLED", device.id());

        if (port.isEnabled()) {
            if (oltDeviceService.isNniPort(device, port)) {
                // NOTE in the NNI case we receive a PORT_REMOVED event with status ENABLED, thus we need to
                // pass the floeAction to the handleNniFlows method
                OltFlowService.FlowOperation action = port.isEnabled() ?
                        OltFlowService.FlowOperation.ADD : OltFlowService.FlowOperation.REMOVE;
                if (type == DeviceEvent.Type.PORT_REMOVED) {
                    action = OltFlowService.FlowOperation.REMOVE;
                }
                oltFlowService.handleNniFlows(device, port, action);
            } else {
                // NOTE if the subscriber was previously provisioned, then provision it again
                ConnectPoint cp = new ConnectPoint(device.id(), port.number());
                Boolean provisionSubscriber = oltFlowService.isSubscriberProvisioned(cp);
                DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                        DiscoveredSubscriber.Status.ADDED, provisionSubscriber);
                if (!discoveredSubscribersQueue.contains(sub)) {
                    log.info("Adding subscriber to queue: {}/{} with status {}",
                            sub.device.id(), sub.port.number(), sub.status);
                    discoveredSubscribersQueue.add(sub);
                }
            }
        } else {
            if (oltDeviceService.isNniPort(device, port)) {
                // NOTE this may need to be handled on DEVICE_REMOVE as we don't disable the NNI
                oltFlowService.handleNniFlows(device, port, OltFlowService.FlowOperation.REMOVE);
            } else {
                // NOTE we are assuming that if a subscriber has default eapol
                // it does not have subscriber flows
                if (oltFlowService.hasDefaultEapol(device.id(), port.number())) {
                    DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                            DiscoveredSubscriber.Status.REMOVED, false);

                    if (!discoveredSubscribersQueue.contains(sub)) {
                        log.info("Adding subscriber to queue: {}/{} with status {}",
                                sub.device.id(), sub.port.number(), sub.status);
                        discoveredSubscribersQueue.add(sub);
                    }
                } else if (
                        oltFlowService.hasSubscriberFlows(device.id(), port.number()) ||
                                oltFlowService.hasDhcpFlows(device.id(), port.number())
                ) {
                    DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                            DiscoveredSubscriber.Status.REMOVED, true);
                    if (!discoveredSubscribersQueue.contains(sub)) {
                        log.info("Adding provisioned subscriber to queue: {}/{} with status {}",
                                sub.device.id(), sub.port.number(), sub.status);
                        discoveredSubscribersQueue.add(sub);
                    }
                }
            }
        }
    }
}
