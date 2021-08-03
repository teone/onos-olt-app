package org.opencord.olt;

import org.onosproject.net.*;

public class OltTestHelpers {

    public static String NNI_PREFIX = "nni-";

    protected class MockOltDeviceService implements OltDeviceInterface {

        public boolean isOlt(Device device) {
            return device.type().equals(Device.Type.OLT);
        }

        public boolean isNniPort(Device device, Port port) {
            return port.annotations().value(AnnotationKeys.PORT_NAME).startsWith(NNI_PREFIX);
        }
    }

    protected class OltPort implements Port {

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
}
