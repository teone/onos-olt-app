package org.opencord.olt.impl;

import org.junit.Before;
import org.junit.Test;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.core.CoreServiceAdapter;
import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.MeterId;
import org.onosproject.store.service.StorageServiceAdapter;

import java.util.LinkedList;
import java.util.List;

import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;


public class OltMeterServiceTest extends OltTestHelpers {
    OltMeterService oltMeterService;
    @Before
    public void setUp() {
        oltMeterService = new OltMeterService();
        oltMeterService.cfgService = new ComponentConfigAdapter();
        oltMeterService.coreService = new CoreServiceAdapter();
        oltMeterService.storageService = new StorageServiceAdapter();
        oltMeterService.activate();
    }

    @Test
    public void testHasMeter() {

        DeviceId deviceId = DeviceId.deviceId("foo");

        // FIXME how do we create a MeterCellId?
        OltMeterService.MeterData meterPending = new OltMeterService.MeterData(MeterId.meterId(1),
                OltMeterService.MeterStatus.PENDING_ADD, "pending");
        OltMeterService.MeterData meterAdded = new OltMeterService.MeterData(MeterId.meterId(2),
                OltMeterService.MeterStatus.ADDED, DEFAULT_BP_ID_DEFAULT);
        List<OltMeterService.MeterData> meters = new LinkedList<>();
        meters.add(meterPending);
        meters.add(meterAdded);
        oltMeterService.programmedMeters.put(deviceId, meters);

        assert oltMeterService.hasMeterByBandwidthProfile(deviceId, DEFAULT_BP_ID_DEFAULT);
        assert oltMeterService.hasMeterByBandwidthProfile(deviceId, "pending");
        assert !oltMeterService.hasMeterByBandwidthProfile(deviceId, "someBandwidthProfile");

        assert !oltMeterService.hasMeterByBandwidthProfile(DeviceId.deviceId("bar"), DEFAULT_BP_ID_DEFAULT);
    }
}