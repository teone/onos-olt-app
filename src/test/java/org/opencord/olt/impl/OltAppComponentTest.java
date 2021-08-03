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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.net.device.DeviceServiceAdapter;
import org.opencord.olt.impl.DiscoveredSubscriber;
import org.opencord.olt.impl.OltAppComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static org.onlab.util.Tools.groupedThreads;

/**
 * Set of tests of the ONOS application component.
 */
public class OltAppComponentTest {

    private OltAppComponent component;
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Before
    public void setUp() {
        component = new OltAppComponent();
        component.cfgService = new ComponentConfigAdapter();
        component.deviceService = new DeviceServiceAdapter();
        component.discoveredSubscribersQueue = new LinkedBlockingQueue<DiscoveredSubscriber>();
        component.discoveredSubscriberExecutor = Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
                "discovered-cp-%d", log));
        component.activate();
    }

    @After
    public void tearDown() {
        component.deactivate();
    }

    @Test
    public void basics() {

    }

}
