package org.opencord.olt.impl;

import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.MeterId;
import org.opencord.sadis.SubscriberAndDeviceInformation;

import java.util.List;
import java.util.Map;

public interface OltMeterServiceInterface {
    /**
     * This method will check for a meter, if not present it will create it and throw an Exception.
     * @param deviceId DeviceId
     * @param bandwidthProfile Bandwidth Profile Id
     * @throws Exception Throws an exception if the meter needs to be created
     */
    void createMeter(DeviceId deviceId, String bandwidthProfile) throws Exception;

    /**
     * This method will check for all the meters specified in the sadis uniTagList,
     * if not present it will create it and throw an Exception.
     * @param deviceId DeviceId
     * @param si SubscriberAndDeviceInformation
     * @throws Exception Throws an exception if any of the meters need to be created
     */
    void createMeters(DeviceId deviceId, SubscriberAndDeviceInformation si) throws Exception;

    boolean hasMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    boolean hasPendingMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile);

    void createMeterForBp(DeviceId deviceId, String bp) throws Exception;

    MeterId getMeterIdForBandwidthProfile(DeviceId deviceId, String bpId);

    void purgeDeviceMeters(DeviceId deviceId);

    Map<DeviceId, List<OltMeterService.MeterData>> getProgrammedMeters();
}
