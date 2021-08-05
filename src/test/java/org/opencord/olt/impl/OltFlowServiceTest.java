package org.opencord.olt.impl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.onlab.packet.ChassisId;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.provider.ProviderId;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;

public class OltFlowServiceTest extends OltTestHelpers {

    private OltFlowService oltFlowService;
    private SadisService sadisService;
    private BaseInformationService<SubscriberAndDeviceInformation> subsService;

    private DeviceId deviceId = DeviceId.deviceId("test-device");
    private Device testDevice = new DefaultDevice(ProviderId.NONE, deviceId, Device.Type.OLT,
            "testManufacturer", "1.0", "1.0", "SN", new ChassisId(1));
    Port uniUpdateEnabled = new OltPort(true, PortNumber.portNumber(16),
            DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, "uni-1").build());
    private DiscoveredSubscriber sub = new DiscoveredSubscriber(testDevice,
            uniUpdateEnabled, DiscoveredSubscriber.Status.ADDED, false);

    @Before
    public void setUp() {
        oltFlowService = new OltFlowService();
        oltFlowService.cfgService = new ComponentConfigAdapter();
        oltFlowService.activate();

        sadisService =  Mockito.spy(new MockSadisService());
        subsService = Mockito.spy(sadisService.getSubscriberInfoService());
    }

    @After
    public void tearDown() {
        oltFlowService.deactivate();
    }

    @Test
    public void testHandleBasicPortFlowsNoEapol() throws Exception {
        oltFlowService.enableEapol = false;

        oltFlowService.handleBasicPortFlows(subsService, sub, DEFAULT_BP_ID_DEFAULT);
        // if eapol is not enabled there's nothing we need to do,
        // so make sure we don't even call sadis
        verify(subsService, never()).get(any());
    }

    @Test
    public void testHandleBasicPortFlowsWithEapolNoMeter() {
        oltFlowService.enableEapol = true;
        // if we need eapol, make sure there is meter for it
        // if there is no meter create one and return

    }
}