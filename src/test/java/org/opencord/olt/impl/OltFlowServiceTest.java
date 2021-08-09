package org.opencord.olt.impl;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.onlab.packet.ChassisId;
import org.onlab.packet.EthType;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flowobjective.DefaultFilteringObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.provider.ProviderId;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;

public class OltFlowServiceTest extends OltTestHelpers {

    private OltFlowService oltFlowService;
    private final ApplicationId testAppId = new DefaultApplicationId(1, "org.opencord.olt.test");

    private final DeviceId deviceId = DeviceId.deviceId("test-device");
    private final Device testDevice = new DefaultDevice(ProviderId.NONE, deviceId, Device.Type.OLT,
            "testManufacturer", "1.0", "1.0", "SN", new ChassisId(1));
    Port uniUpdateEnabled = new OltPort(true, PortNumber.portNumber(16),
            DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, "uni-1").build());
    private final DiscoveredSubscriber addedSub = new DiscoveredSubscriber(testDevice,
            uniUpdateEnabled, DiscoveredSubscriber.Status.ADDED, false);
    private final DiscoveredSubscriber removedSub = new DiscoveredSubscriber(testDevice,
            uniUpdateEnabled, DiscoveredSubscriber.Status.REMOVED, false);

    @Before
    public void setUp() {
        oltFlowService = new OltFlowService();
        oltFlowService.cfgService = new ComponentConfigAdapter();
        oltFlowService.sadisService = Mockito.mock(SadisService.class);
        oltFlowService.coreService = Mockito.spy(new CoreServiceAdapter());
        oltFlowService.oltMeterService = Mockito.mock(OltMeterService.class);
        oltFlowService.flowObjectiveService = Mockito.mock(FlowObjectiveService.class);

        doReturn(Mockito.mock(BaseInformationService.class))
                .when(oltFlowService.sadisService).getSubscriberInfoService();
        doReturn(testAppId).when(oltFlowService.coreService).registerApplication("org.opencord.olt");
        oltFlowService.activate();
    }

    @After
    public void tearDown() {
        oltFlowService.deactivate();
    }

    @Test
    public void testHasDefaultEapol() {
        DeviceId deviceId = DeviceId.deviceId("test-device");
        ConnectPoint cpWithStatus = new ConnectPoint(deviceId, PortNumber.portNumber(16));

        OltFlowService.OltPortStatus portStatusAdded = new OltFlowService.OltPortStatus(
                OltFlowService.OltFlowsStatus.ADDED,
                OltFlowService.OltFlowsStatus.NONE,
                null
        );

        OltFlowService.OltPortStatus portStatusRemoved = new OltFlowService.OltPortStatus(
                OltFlowService.OltFlowsStatus.REMOVED,
                OltFlowService.OltFlowsStatus.NONE,
                null
        );

        oltFlowService.cpStatus.put(cpWithStatus, portStatusAdded);
        Assert.assertTrue(oltFlowService.hasDefaultEapol(deviceId, PortNumber.portNumber(16)));

        oltFlowService.cpStatus.put(cpWithStatus, portStatusRemoved);
        Assert.assertFalse(oltFlowService.hasDefaultEapol(deviceId, PortNumber.portNumber(16)));

        Assert.assertFalse(oltFlowService.hasDefaultEapol(deviceId, PortNumber.portNumber(17)));
    }

    @Test
    public void testHandleBasicPortFlowsNoEapol() throws Exception {
        oltFlowService.enableEapol = false;

        oltFlowService.handleBasicPortFlows(addedSub, DEFAULT_BP_ID_DEFAULT);
        // if eapol is not enabled there's nothing we need to do,
        // so make sure we don't even call sadis
        verify(oltFlowService.subsService, never()).get(any());
    }

    @Test(expected = Exception.class)
    public void testHandleBasicPortFlowsWithEapolNoMeter() throws Exception {
        // if we need eapol, make sure there is meter for it
        // if there is no meter create one and return

        oltFlowService.handleBasicPortFlows(addedSub, DEFAULT_BP_ID_DEFAULT);

        // we check for an existing meter (not present)
        verify(oltFlowService.oltMeterService, times(1))
                .hasMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        // we check for a pending meter (not present)
        verify(oltFlowService.oltMeterService, times(1))
                .hasPendingMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        // we create the meter
        verify(oltFlowService.oltMeterService, times(1))
                .createMeterForBp(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));
    }

    @Test(expected = Exception.class)
    public void testHandleBasicPortFlowsWithEapolPendingMeter() throws Exception {
        // we already have a pending meter, so we just wait for it to be installed
        doReturn(true).when(oltFlowService.oltMeterService)
                .hasPendingMeterByBandwidthProfile(addedSub.device.id(), DEFAULT_BP_ID_DEFAULT);
        oltFlowService.handleBasicPortFlows(addedSub, DEFAULT_BP_ID_DEFAULT);

        // we check for an existing meter (not present)
        verify(oltFlowService.oltMeterService, times(1))
                .hasMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        // we check for a pending meter (present)
        verify(oltFlowService.oltMeterService, times(1))
                .hasPendingMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        // we do not create the meter (it's already PENDING_ADD)
        verify(oltFlowService.oltMeterService, never())
                .createMeterForBp(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));
    }

    @Test
    public void testHandleBasicPortFlowsWithEapolAddedMeter() throws Exception {
        // this is the happy case, we have the meter so we check that the default EAPOL flow
        // is installed
        doReturn(true).when(oltFlowService.oltMeterService)
                .hasMeterByBandwidthProfile(addedSub.device.id(), DEFAULT_BP_ID_DEFAULT);
        doReturn(MeterId.meterId(1)).when(oltFlowService.oltMeterService)
                .getMeterIdForBandwidthProfile(addedSub.device.id(), DEFAULT_BP_ID_DEFAULT);

        FilteringObjective expectedFilter = DefaultFilteringObjective.builder()
                .permit()
                .withKey(Criteria.matchInPort(addedSub.port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.EAPOL.ethType()))
                .fromApp(testAppId)
                .withPriority(10000)
                .add();


        oltFlowService.handleBasicPortFlows(addedSub, DEFAULT_BP_ID_DEFAULT);

        // we check for an existing meter (present)
        verify(oltFlowService.oltMeterService, times(1))
                .hasMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        // the meter exist, no need to check for PENDING or to create it
        verify(oltFlowService.oltMeterService, never())
                .hasPendingMeterByBandwidthProfile(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));
        verify(oltFlowService.oltMeterService, never())
                .createMeterForBp(eq(addedSub.device.id()), eq(DEFAULT_BP_ID_DEFAULT));

        verify(oltFlowService.flowObjectiveService, times(1))
                .filter(eq(addedSub.device.id()), argThat(new FilteringObjectiveMatcher(expectedFilter)));
    }

    @Test
    public void testHandleBasicPortFlowsRemovedSub() throws Exception {
        // we are testing that when a port goes down we remove the default EAPOL flow

        doReturn(MeterId.meterId(1)).when(oltFlowService.oltMeterService)
                .getMeterIdForBandwidthProfile(addedSub.device.id(), DEFAULT_BP_ID_DEFAULT);

        FilteringObjective expectedFilter = DefaultFilteringObjective.builder()
                .deny()
                .withKey(Criteria.matchInPort(addedSub.port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.EAPOL.ethType()))
                .fromApp(testAppId)
                .withPriority(10000)
                .add();

        oltFlowService.handleBasicPortFlows(removedSub, DEFAULT_BP_ID_DEFAULT);

        verify(oltFlowService.flowObjectiveService, times(1))
                .filter(eq(addedSub.device.id()), argThat(new FilteringObjectiveMatcher(expectedFilter)));
    }
}