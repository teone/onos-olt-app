/*
 * Copyright 2021-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencord.olt.impl;

import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.opencord.sadis.SadisService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.onlab.util.Tools.get;
import static org.onlab.util.Tools.groupedThreads;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_MCAST_SERVICE_NAME;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_MCAST_SERVICE_NAME_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.EAPOL_DELETE_RETRY_MAX_ATTEMPS;
import static org.opencord.olt.impl.OsgiPropertyConstants.EAPOL_DELETE_RETRY_MAX_ATTEMPS_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.PROVISION_DELAY;
import static org.opencord.olt.impl.OsgiPropertyConstants.PROVISION_DELAY_DEFAULT;

/**
 * OLT Application.
 */
@Component(immediate = true,
        property = {
                DEFAULT_BP_ID + ":String=" + DEFAULT_BP_ID_DEFAULT,
                DEFAULT_MCAST_SERVICE_NAME + ":String=" + DEFAULT_MCAST_SERVICE_NAME_DEFAULT,
                EAPOL_DELETE_RETRY_MAX_ATTEMPS + ":Integer=" +
                        EAPOL_DELETE_RETRY_MAX_ATTEMPS_DEFAULT,
                PROVISION_DELAY + ":Integer=" + PROVISION_DELAY_DEFAULT,
        })
public class Olt {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected volatile SadisService sadisService;

    /**
     * Default bandwidth profile id that is used for authentication trap flows.
     **/
    protected String defaultBpId = DEFAULT_BP_ID_DEFAULT;

    /**
     * Default multicast service name.
     **/
    protected String multicastServiceName = DEFAULT_MCAST_SERVICE_NAME_DEFAULT;

    /**
     * Default amounts of eapol retry.
     **/
    protected int eapolDeleteRetryMaxAttempts = EAPOL_DELETE_RETRY_MAX_ATTEMPS_DEFAULT;

    /**
     * Delay between EAPOL removal and data plane flows provisioning.
     */
    protected int provisionDelay = PROVISION_DELAY_DEFAULT;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltDeviceServiceInterface oltDeviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltFlowServiceInterface oltFlowService;

    protected BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue =
            new LinkedBlockingQueue<DiscoveredSubscriber>();
    private DeviceListener deviceListener;
    protected ScheduledExecutorService discoveredSubscriberExecutor =
            Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
                    "discovered-cp-%d", log));

    private String someProperty;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        deviceListener = new OltDeviceListener(oltDeviceService, discoveredSubscribersQueue);
        deviceService.addListener(deviceListener);
        discoveredSubscriberExecutor.execute(this::processDiscoveredSubscribers);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        deviceService.removeListener(deviceListener);
        discoveredSubscriberExecutor.shutdown();
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            String bpId = get(properties, DEFAULT_BP_ID);
            defaultBpId = isNullOrEmpty(bpId) ? defaultBpId : bpId;

            String mcastSN = get(properties, DEFAULT_MCAST_SERVICE_NAME);
            multicastServiceName = isNullOrEmpty(mcastSN) ? multicastServiceName : mcastSN;

            String eapolDeleteRetryNew = get(properties, EAPOL_DELETE_RETRY_MAX_ATTEMPS);
            eapolDeleteRetryMaxAttempts = isNullOrEmpty(eapolDeleteRetryNew) ? EAPOL_DELETE_RETRY_MAX_ATTEMPS_DEFAULT :
                    Integer.parseInt(eapolDeleteRetryNew.trim());

            log.debug("OLT properties: DefaultBpId: {}, MulticastServiceName: {}, EapolDeleteRetryMaxAttempts: {}",
                    defaultBpId, multicastServiceName, eapolDeleteRetryMaxAttempts);
        }
        log.info("Reconfigured");
    }

    protected void bindSadisService(SadisService service) {
        oltDeviceService.bindSadisService(service);
        sadisService = service;
    }

    protected void unbindSadisService(SadisService service) {
        oltDeviceService.unbindSadisService();
        sadisService = null;
    }

    private void processDiscoveredSubscribers() {
        log.info("Started processDiscoveredSubscribers loop");
        while (true) {
            if (!discoveredSubscribersQueue.isEmpty()) {
                DiscoveredSubscriber sub = discoveredSubscribersQueue.peek();
                log.info("Processing discovered subscriber on port {}/{}", sub.device.id(), sub.port.number());

                if (sub.status == DiscoveredSubscriber.Status.ADDED) {
                    if (sub.provisionSubscriber) {
                        try {
                            oltFlowService.handleSubscriberFlows(sub);
                            discoveredSubscribersQueue.remove(sub);
                        } catch (Exception e) {
                            log.error(e.getMessage());
                            continue;
                        }
                    } else {
                        try {
                            oltFlowService.handleBasicPortFlows(
                                    sadisService.getSubscriberInfoService(), sub, defaultBpId);
                            discoveredSubscribersQueue.remove(sub);
                        } catch (Exception e) {
                            // we get an exception if something is not ready and we'll need
                            // to retry later (eg: the meter is still being installed)
                            log.error("Processing of subscriber {}/{} postponed: {}",
                                    sub.device.id(), sub.port.number(), e.getMessage());
                            try {
                                TimeUnit.MILLISECONDS.sleep(500);
                            } catch (Exception ex) {
                                continue;
                            }
                            continue;
                        }
                    }

                } else if (sub.status == DiscoveredSubscriber.Status.REMOVED) {
                    log.warn("currently not handling removed subscribers, removing it from queue: {}/{}",
                            sub.device.id(), sub.port.number());
                    discoveredSubscribersQueue.remove(sub);
                }
            }
            // temporary code to slow down processing while testing,
            // to be removed
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (Exception e) {
                continue;
            }
        }
    }
}
