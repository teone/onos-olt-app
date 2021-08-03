package org.opencord.olt;

import org.junit.Before;
import org.junit.Test;
import org.onlab.packet.ChassisId;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.provider.ProviderId;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OltDeviceListenerTest extends OltTestHelpers {
    private OltDeviceListener oltDeviceListener;
    protected BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue = new LinkedBlockingQueue<DiscoveredSubscriber>();

    private DeviceId deviceId = DeviceId.deviceId("test-device");
    private Device testDevice = new DefaultDevice(ProviderId.NONE, deviceId, Device.Type.OLT, "testManufacturer", "1.0.0", "1.0.0", "SN", new ChassisId(1));

    @Before
    public void setUp() {
        MockOltDeviceService mockOltDeviceService = new MockOltDeviceService();
        oltDeviceListener = new OltDeviceListener(mockOltDeviceService, discoveredSubscribersQueue);
    }

    @Test
    public void testNniEvent() {
        Port nniPort = new OltPort(true, PortNumber.portNumber(1048576), DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, NNI_PREFIX + "1").build());
        DeviceEvent event = new DeviceEvent(DeviceEvent.Type.PORT_ADDED, testDevice, nniPort);
        oltDeviceListener.event(event);

        // NNI events are straight forward, we can provision the flows directly
        assert discoveredSubscribersQueue.isEmpty();
    }

    @Test
    public void testUniEvents() {
        DiscoveredSubscriber sub;
        // there are few cases we need to test in the UNI port case:
        // - [X] UNI port added in disabled state
        // - UNI port added in enabled state
        // - [X] UNI port updated to enabled state
        // - UNI port updated to disabled state
        // - UNI port removed (assumes it's disabled state)

        Port uniAddedDisabled = new OltPort(false, PortNumber.portNumber(16), DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, "uni-1").build());
        DeviceEvent uniAddedDisabledEvent = new DeviceEvent(DeviceEvent.Type.PORT_ADDED, testDevice, uniAddedDisabled);
        oltDeviceListener.event(uniAddedDisabledEvent);

        assert !discoveredSubscribersQueue.isEmpty();
        sub = discoveredSubscribersQueue.poll();
        assert !sub.provisionSubscriber; // this is not a provision subscriber call
        assert sub.device.equals(testDevice);
        assert sub.port.equals(uniAddedDisabled);
        assert sub.status.equals(DiscoveredSubscriber.Status.REMOVED); // we need to remove flows for this port (if any)
        assert discoveredSubscribersQueue.isEmpty(); // the queue is now empty

        Port uniUpdateEnabled = new OltPort(true, PortNumber.portNumber(16), DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, "uni-1").build());
        DeviceEvent uniUpdateEnabledEvent = new DeviceEvent(DeviceEvent.Type.PORT_UPDATED, testDevice, uniUpdateEnabled);
        oltDeviceListener.event(uniUpdateEnabledEvent);

        assert !discoveredSubscribersQueue.isEmpty();
        sub = discoveredSubscribersQueue.poll();
        assert !sub.provisionSubscriber; // this is not a provision subscriber call
        assert sub.device.equals(testDevice);
        assert sub.port.equals(uniUpdateEnabled);
        assert sub.status.equals(DiscoveredSubscriber.Status.ADDED); // we need to remove flows for this port (if any)
        assert discoveredSubscribersQueue.isEmpty(); // the queue is now empty
    }
}
