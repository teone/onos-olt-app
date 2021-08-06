package org.opencord.olt.impl;

import org.onosproject.net.Device;
import org.onosproject.net.Port;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class OltDeviceListener implements DeviceListener {
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final OltDeviceServiceInterface oltDevice;
    private final BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue;

    public OltDeviceListener(OltDeviceServiceInterface oltDevice,
                             BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue) {
        this.discoveredSubscribersQueue = discoveredSubscribersQueue;
        this.oltDevice = oltDevice;
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
            default:
                log.debug("OltDeviceListener receives event: {}", event);
        }
    }

    private void handleOltPort(DeviceEvent.Type type, Device device, Port port) {
        log.info("OltDeviceListener receives event {} for port {} with status {} on device {}", type, port.number(),
                port.isEnabled() ? "ENABLED" : "DISABLED", device.id());

        if (port.isEnabled()) {
            if (oltDevice.isNniPort(device, port)) {
                log.warn("TODO handle NNI flows add");
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
                log.warn("TODO handle NNI flows remove");
            } else {
                DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                        DiscoveredSubscriber.Status.REMOVED, false);
                if (!discoveredSubscribersQueue.contains(sub)) {
                    log.info("Adding subscriber to queue: {}/{} with status {}",
                            sub.device.id(), sub.port.number(), sub.status);
                    discoveredSubscribersQueue.add(sub);
                }
            }
        }
    }
}
