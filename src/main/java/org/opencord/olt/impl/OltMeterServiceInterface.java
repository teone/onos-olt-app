package org.opencord.olt.impl;

import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.MeterId;

import java.util.HashMap;
import java.util.List;

public interface OltMeterServiceInterface {
    boolean hasMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    boolean hasPendingMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    void createMeterForBp(DeviceId deviceId, String bp) throws Exception;

    MeterId getMeterIdForBandwidthProfile(DeviceId deviceId, String bpId);

    HashMap<DeviceId, List<OltMeterService.MeterData>> getProgrammedMeters();
}
