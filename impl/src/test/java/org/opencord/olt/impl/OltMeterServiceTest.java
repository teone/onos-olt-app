package org.opencord.olt.impl;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterServiceAdapter;
import org.onosproject.net.meter.MeterState;
import org.onosproject.store.service.StorageServiceAdapter;
import org.onosproject.store.service.TestStorageService;
import org.opencord.sadis.SadisService;

import java.util.LinkedList;
import java.util.List;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;


public class OltMeterServiceTest extends OltTestHelpers {
    OltMeterService oltMeterService;
    OltMeterService component;

    @Before
    public void setUp() {
        component = new OltMeterService();
        component.cfgService = new ComponentConfigAdapter();
        component.coreService = new CoreServiceAdapter();
        component.storageService = new StorageServiceAdapter();
        component.sadisService = Mockito.mock(SadisService.class);
        component.meterService = new MeterServiceAdapter();
        component.storageService = new TestStorageService();
        component.activate();

        // we're spying on the component under test so that we can mock the return of iternal methods
        oltMeterService = Mockito.spy(component);
    }

    @Test
    public void testHasMeter() {

        DeviceId deviceId = DeviceId.deviceId("foo");

        // FIXME how do we create a MeterCellId?
        MeterData meterPending = new MeterData(MeterId.meterId(1), null,
                                               MeterState.PENDING_ADD, "pending");
        MeterData meterAdded = new MeterData(MeterId.meterId(2), null,
                                             MeterState.ADDED, DEFAULT_BP_ID_DEFAULT);
        List<MeterData> meters = new LinkedList<>();
        meters.add(meterPending);
        meters.add(meterAdded);
        oltMeterService.programmedMeters.put(deviceId, meters);

        assert oltMeterService.hasMeterByBandwidthProfile(deviceId, DEFAULT_BP_ID_DEFAULT);
        assert !oltMeterService.hasMeterByBandwidthProfile(deviceId, "pending");
        assert !oltMeterService.hasMeterByBandwidthProfile(deviceId, "someBandwidthProfile");

        assert !oltMeterService.hasMeterByBandwidthProfile(DeviceId.deviceId("bar"), DEFAULT_BP_ID_DEFAULT);
    }

    @Test
    public void testGetMeterId() {
        DeviceId deviceId = DeviceId.deviceId("foo");
        MeterData meterPending = new MeterData(MeterId.meterId(1), null,
                                               MeterState.PENDING_ADD, "pending");
        MeterData meterAdded = new MeterData(MeterId.meterId(2), null,
                                             MeterState.ADDED, DEFAULT_BP_ID_DEFAULT);
        List<MeterData> meters = new LinkedList<>();
        meters.add(meterPending);
        meters.add(meterAdded);
        oltMeterService.programmedMeters.put(deviceId, meters);

        Assert.assertNull(oltMeterService.getMeterIdForBandwidthProfile(deviceId, "pending"));
        Assert.assertEquals(MeterId.meterId(2),
                oltMeterService.getMeterIdForBandwidthProfile(deviceId, DEFAULT_BP_ID_DEFAULT));
    }

    @Test
    public void testCreateMeter() {

        DeviceId deviceId = DeviceId.deviceId("foo");
        String bp = "Default";

        // if we already have a meter do nothing and return true
        doReturn(true).when(oltMeterService).hasMeterByBandwidthProfile(deviceId, bp);
        Assert.assertTrue(oltMeterService.createMeter(deviceId, bp));
        verify(oltMeterService, never()).createMeterForBp(any(), any());

        // if we have a pending meter, do nothing and return false
        doReturn(false).when(oltMeterService).hasMeterByBandwidthProfile(deviceId, bp);
        doReturn(true).when(oltMeterService).hasPendingMeterByBandwidthProfile(deviceId, bp);
        Assert.assertFalse(oltMeterService.createMeter(deviceId, bp));
        verify(oltMeterService, never()).createMeterForBp(any(), any());

        // if the meter is not present at all, create it and return false
        doReturn(false).when(oltMeterService).hasMeterByBandwidthProfile(deviceId, bp);
        doReturn(false).when(oltMeterService).hasPendingMeterByBandwidthProfile(deviceId, bp);
        Assert.assertFalse(oltMeterService.createMeter(deviceId, bp));
        verify(oltMeterService, times(1)).createMeterForBp(deviceId, bp);
    }
}