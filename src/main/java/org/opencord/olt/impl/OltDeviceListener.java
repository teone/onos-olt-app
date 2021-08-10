package org.opencord.olt.impl;

import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class OltDeviceListener implements DeviceListener {
    private final Logger log = LoggerFactory.getLogger(getClass());
    protected final OltDeviceServiceInterface oltDevice;
    protected final OltFlowServiceInterface oltFlowService;
    protected final OltMeterServiceInterface oltMeterService;
    protected final DeviceService deviceService;
    private final BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue;

    public OltDeviceListener(OltDeviceServiceInterface oltDevice,
                             OltFlowServiceInterface oltFlowService,
                             OltMeterServiceInterface oltMeterService,
                             DeviceService deviceService,
                             BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue) {
        this.oltDevice = oltDevice;
        this.oltFlowService = oltFlowService;
        this.oltMeterService = oltMeterService;
        this.deviceService = deviceService;
        this.discoveredSubscribersQueue = discoveredSubscribersQueue;
    }

    @Override
    public void event(DeviceEvent event) {
        if (!oltDevice.isOlt(event.subject())) {
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
                // port added, updated and removed are treated in the same way as we only care whether the port
                // is enabled or not
                handleOltPort(event.type(), event.subject(), event.port());
                return;
            case DEVICE_AVAILABILITY_CHANGED:
                DeviceId deviceId = event.subject().id();
                if (!deviceService.isAvailable(deviceId) && deviceService.getPorts(deviceId).isEmpty()) {
                    log.info("Device {} availability changed to false, purging meters and flows", deviceId);
                    oltFlowService.purgeDeviceFlows(deviceId);
                    oltMeterService.purgeDeviceMeters(deviceId);
                }
            default:
                log.debug("OltDeviceListener receives event: {}", event);
        }
    }

    private void handleOltPort(DeviceEvent.Type type, Device device, Port port) {
        log.info("OltDeviceListener receives event {} for port {} with status {} on device {}", type, port.number(),
                port.isEnabled() ? "ENABLED" : "DISABLED", device.id());

        if (port.isEnabled()) {
            if (oltDevice.isNniPort(device, port)) {
                // NOTE in the NNI case we receive a PORT_REMOVED event with status ENABLED, thus we need to
                // pass the floeAction to the handleNniFlows method
                OltFlowService.FlowAction action = port.isEnabled() ?
                        OltFlowService.FlowAction.ADD : OltFlowService.FlowAction.REMOVE;
                if (type == DeviceEvent.Type.PORT_REMOVED) {
                    action = OltFlowService.FlowAction.REMOVE;
                }
                oltFlowService.handleNniFlows(device, port, action);
            } else {
                DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                        DiscoveredSubscriber.Status.ADDED, false);
                if (!discoveredSubscribersQueue.contains(sub)) {
                    log.info("Adding subscriber to queue: {}/{} with status {}",
                            sub.device.id(), sub.port.number(), sub.status);
                    discoveredSubscribersQueue.add(sub);
                }
            }
        } else {
            if (oltDevice.isNniPort(device, port)) {
                // NOTE this may need to be handled on DEVICE_REMOVE as we don't disable the NNI
                oltFlowService.handleNniFlows(device, port, OltFlowService.FlowAction.REMOVE);
            } else {

                if (oltFlowService.hasDefaultEapol(device.id(), port.number())) {
                    DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                            DiscoveredSubscriber.Status.REMOVED, false);

                    if (!discoveredSubscribersQueue.contains(sub)) {
                        log.info("Adding subscriber to queue: {}/{} with status {}",
                                sub.device.id(), sub.port.number(), sub.status);
                        discoveredSubscribersQueue.add(sub);
                    }
                }
                // TODO we need to check if we have subcriber on this port
                // and if that's the case we should add an entry to the queue
            }
        }
    }
}
