package org.opencord.olt.impl;

public interface OltFlowServiceInterface {

    void handleBasicPortFlows(
            DiscoveredSubscriber sub, String defaultMeterId)
                throws Exception;

    void handleSubscriberFlows(DiscoveredSubscriber sub) throws Exception;

}
