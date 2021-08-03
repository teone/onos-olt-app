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
package org.opencord.olt;

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
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.onlab.util.Tools.get;

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

    private OltDeviceService oltDevice = new OltDeviceService();

    protected BlockingQueue<DiscoveredSubscriber> discoveredSubscribersQueue = new LinkedBlockingQueue<DiscoveredSubscriber>();
    private final DeviceListener deviceListener = new OltDeviceListener(oltDevice, (BlockingDeque<DiscoveredSubscriber>) discoveredSubscribersQueue);


    private String someProperty;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        deviceService.addListener(deviceListener);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        deviceService.removeListener(deviceListener);
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


}
