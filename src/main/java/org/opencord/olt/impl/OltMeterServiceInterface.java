package org.opencord.olt.impl;

import org.onosproject.net.DeviceId;

public interface OltMeterServiceInterface {
    boolean hasMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    boolean hasPendingMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    void createMeterForBp(DeviceId deviceId, String bp);
}
