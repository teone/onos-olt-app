package org.opencord.olt.impl;

import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.MeterId;

import java.util.List;
import java.util.Map;

public interface OltMeterServiceInterface {
    boolean hasMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    boolean hasPendingMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    void createMeterForBp(DeviceId deviceId, String bp) throws Exception;

    MeterId getMeterIdForBandwidthProfile(DeviceId deviceId, String bpId);

    Map<DeviceId, List<OltMeterService.MeterData>> getProgrammedMeters();
}
