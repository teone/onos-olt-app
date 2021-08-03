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
import org.osgi.service.component.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;
import java.util.concurrent.*;

import static org.onlab.util.Tools.get;
import static org.onlab.util.Tools.groupedThreads;

/**
 * OLT Application
 */
@Component(immediate = true,
           property = {
            "someProperty:String=useless-for-now"
           })
public class OltAppComponent {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL,
            bind = "bindSadisService",
            unbind = "unbindSadisService",
            policy = ReferencePolicy.DYNAMIC)
    protected volatile SadisService sadisService;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private OltDeviceServiceInterface oltDevice = new OltDeviceService();
    private OltFlowServiceInterface oltFlowService = new OltFlowService();

    protected BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue = new LinkedBlockingQueue<DiscoveredSubscriber>();
    private DeviceListener deviceListener;
    protected ScheduledExecutorService discoveredSubscriberExecutor = Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
            "discovered-cp-%d", log));

    private String someProperty;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        deviceListener = new OltDeviceListener(oltDevice, discoveredSubscribersQueue);
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
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    protected void bindSadisService(SadisService service) {
        oltDevice.bindSadisService(service);
        sadisService = service;
    }

    protected void unbindSadisService(SadisService service) {
        oltDevice.unbindSadisService();
        sadisService = null;
    }

    private void processDiscoveredSubscribers() {
        log.info("Started processDiscoveredSubscribers loop");
        while (true) {
            if (!discoveredSubscribersQueue.isEmpty()) {
                DiscoveredSubscriber sub = discoveredSubscribersQueue.peek();

                if (sub.status == DiscoveredSubscriber.Status.ADDED) {
                    if (sub.provisionSubscriber) {
                        try {
                            oltFlowService.handleSubscriberFlows(sub);
                            discoveredSubscribersQueue.remove(sub);
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        }
                    } else {
                        try {
                            oltFlowService.handleBasicPortFlows(sub);
                            discoveredSubscribersQueue.remove(sub);
                        } catch (Exception e) {
                            log.error(e.getMessage());
                        }
                    }

                } else if (sub.status == DiscoveredSubscriber.Status.REMOVED) {
                    log.warn("currently not handling removed subscribers, removing it from queue: {}", sub);
                    discoveredSubscribersQueue.remove(sub);
                }
            }
            try {
                TimeUnit.SECONDS.sleep(5);
            } catch (Exception e) {
                continue;
            }
        }
    }
}
