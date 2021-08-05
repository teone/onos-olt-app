package org.opencord.olt.impl;

import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SubscriberAndDeviceInformation;

public interface OltFlowServiceInterface {

    void handleBasicPortFlows(
            BaseInformationService<SubscriberAndDeviceInformation> subsService,
            DiscoveredSubscriber sub, String defaultMeterId)
                throws Exception;

    void handleSubscriberFlows(DiscoveredSubscriber sub) throws Exception;

}
