package org.opencord.olt.impl;

import com.google.common.collect.Maps;
import org.mockito.ArgumentMatcher;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.MacAddress;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.Annotations;
import org.onosproject.net.Device;
import org.onosproject.net.Element;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.opencord.sadis.BandwidthProfileInformation;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;

import java.util.Map;

@SuppressWarnings("checkstyle:HideUtilityClassConstructor")
public class OltTestHelpers {

    protected static final String CLIENT_NAS_PORT_ID = "PON 1/1";
    protected static final String CLIENT_CIRCUIT_ID = "CIR-PON 1/1";
    protected static final String OLT_DEV_ID = "of:00000000000000aa";
    Map<String, BandwidthProfileInformation> bpInformation = Maps.newConcurrentMap();

    public static String nniPrefix = "nni-";

    protected class FilteringObjectiveMatcher extends ArgumentMatcher<FilteringObjective> {

        private FilteringObjective left;

        public FilteringObjectiveMatcher(FilteringObjective left) {
            this.left = left;
        }

        @Override
        public boolean matches(Object right) {
            // NOTE this matcher can be improved
            FilteringObjective r = (FilteringObjective) right;
            return left.type().equals(r.type()) &&
                    left.key().equals(r.key()) &&
                    left.conditions().equals(r.conditions()) &&
                    left.appId().equals(r.appId()) &&
                    left.priority() == r.priority();
        }
    }

    // use mockito
    @Deprecated
    protected class MockOltDeviceServiceService implements OltDeviceServiceInterface {

        public boolean isOlt(Device device) {
            return device.type().equals(Device.Type.OLT);
        }

        public boolean isNniPort(Device device, Port port) {
            return port.annotations().value(AnnotationKeys.PORT_NAME).startsWith(nniPrefix);
        }

        @Override
        public void bindSadisService(SadisService service) {}

        @Override
        public void unbindSadisService() {}
    }

    public class OltPort implements Port {

        public boolean enabled;
        public PortNumber portNumber;
        public Annotations annotations;

        public OltPort(boolean enabled, PortNumber portNumber, Annotations annotations) {
            this.enabled = enabled;
            this.portNumber = portNumber;
            this.annotations = annotations;
        }

        @Override
        public Element element() {
            return null;
        }

        @Override
        public PortNumber number() {
            return portNumber;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public Type type() {
            return null;
        }

        @Override
        public long portSpeed() {
            return 0;
        }

        @Override
        public Annotations annotations() {
            return annotations;
        }
    }

    // use mockito instead
    @Deprecated
    protected class MockSadisService implements SadisService {

        @Override
        public BaseInformationService<SubscriberAndDeviceInformation> getSubscriberInfoService() {
            return new MockSubService();
        }

        @Override
        public BaseInformationService<BandwidthProfileInformation> getBandwidthProfileService() {
            return new MockBpService();
        }
    }

    // use mockito
    @Deprecated
    private class MockBpService implements BaseInformationService<BandwidthProfileInformation> {
        @Override
        public void clearLocalData() {

        }

        @Override
        public void invalidateAll() {

        }

        @Override
        public void invalidateId(String id) {

        }

        @Override
        public BandwidthProfileInformation get(String id) {
            return bpInformation.get(id);
        }

        @Override
        public BandwidthProfileInformation getfromCache(String id) {
            return null;
        }
    }

    // use mockito
    @Deprecated
    private class MockSubService implements BaseInformationService<SubscriberAndDeviceInformation> {
        MockSubscriberAndDeviceInformation sub =
                new MockSubscriberAndDeviceInformation(CLIENT_NAS_PORT_ID,
                        CLIENT_NAS_PORT_ID, CLIENT_CIRCUIT_ID, null, null);

        @Override
        public SubscriberAndDeviceInformation get(String id) {
            return sub;
        }

        @Override
        public void clearLocalData() {

        }

        @Override
        public void invalidateAll() {
        }

        @Override
        public void invalidateId(String id) {
        }

        @Override
        public SubscriberAndDeviceInformation getfromCache(String id) {
            return null;
        }
    }

    // use mockito
    @Deprecated
    private class MockSubscriberAndDeviceInformation extends SubscriberAndDeviceInformation {

        MockSubscriberAndDeviceInformation(String id, String nasPortId,
                                           String circuitId, MacAddress hardId,
                                           Ip4Address ipAddress) {
            this.setHardwareIdentifier(hardId);
            this.setId(id);
            this.setIPAddress(ipAddress);
            this.setNasPortId(nasPortId);
            this.setCircuitId(circuitId);
        }
    }
}
