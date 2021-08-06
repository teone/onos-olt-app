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
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.runners.MockitoJUnitRunner;
import org.onlab.packet.ChassisId;
import org.onosproject.cfg.ComponentConfigAdapter;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.DefaultDevice;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceServiceAdapter;
import org.onosproject.net.provider.ProviderId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.onlab.util.Tools.groupedThreads;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_BP_ID_DEFAULT;

/**
 * Set of tests of the ONOS application component.
 */

@RunWith(MockitoJUnitRunner.class)
public class OltTest extends OltTestHelpers {

    private Olt component;
    private final Logger log = LoggerFactory.getLogger(getClass());


    private DeviceId deviceId = DeviceId.deviceId("test-device");
    private Device testDevice = new DefaultDevice(ProviderId.NONE, deviceId, Device.Type.OLT,
            "testManufacturer", "1.0", "1.0", "SN", new ChassisId(1));
    Port uniUpdateEnabled = new OltPort(true, PortNumber.portNumber(16),
            DefaultAnnotations.builder().set(AnnotationKeys.PORT_NAME, "uni-1").build());
    private DiscoveredSubscriber sub = new DiscoveredSubscriber(testDevice,
            uniUpdateEnabled, DiscoveredSubscriber.Status.ADDED, false);

    @Before
    public void setUp() {
        component = new Olt();
        component.cfgService = new ComponentConfigAdapter();
        component.deviceService = new DeviceServiceAdapter();
        component.discoveredSubscribersQueue = new LinkedBlockingQueue<DiscoveredSubscriber>();
        component.discoveredSubscriberExecutor = Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
                "discovered-cp-%d", log));
        component.oltFlowService = Mockito.mock(OltFlowService.class);
        component.sadisService = new MockSadisService();

        // reset the spy on oltFlowService
        reset(component.oltFlowService);
        component.discoveredSubscribersQueue.clear();

        component.activate();
    }

    @After
    public void tearDown() {
        component.deactivate();
    }

    @Test
    public void testProcessDiscoveredSubscribersBasicPortSuccess() throws Exception {

        // adding the discovered subscriber to the queue
        component.discoveredSubscribersQueue.add(sub);

        // check that we're calling the correct method
        TimeUnit.SECONDS.sleep(1);
        verify(component.oltFlowService, times(1)).handleBasicPortFlows(eq(sub),
                eq(DEFAULT_BP_ID_DEFAULT));

        // check if the method doesn't throw an exception we're removing the subscriber from the queue
        assert component.discoveredSubscribersQueue.isEmpty();
    }

    @Test
    public void testProcessDiscoveredSubscribersBasicPortException() throws Exception {
        doThrow(Exception.class).when(component.oltFlowService).handleBasicPortFlows(any(),
                eq(DEFAULT_BP_ID_DEFAULT));

        // adding the discovered subscriber to the queue
        component.discoveredSubscribersQueue.add(sub);

        // check that we're calling the correct method,
        // since the subscriber is not removed from the queue we're calling the method multiple times
        TimeUnit.SECONDS.sleep(1);
        verify(component.oltFlowService, atLeastOnce()).handleBasicPortFlows(eq(sub), eq(DEFAULT_BP_ID_DEFAULT));

        // check if the method throws an exception we're not removing the subscriber from the queue
        assert component.discoveredSubscribersQueue.size() == 1;
    }

}
