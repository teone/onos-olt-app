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
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.device.DeviceStore;
import org.opencord.olt.OltService;
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

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.onlab.util.Tools.get;
import static org.onlab.util.Tools.groupedThreads;
import static org.opencord.olt.impl.OsgiPropertyConstants.*;

/**
 * OLT Application.
 */
@Component(immediate = true,
        property = {
                DEFAULT_BP_ID + ":String=" + DEFAULT_BP_ID_DEFAULT,
                DEFAULT_MCAST_SERVICE_NAME + ":String=" + DEFAULT_MCAST_SERVICE_NAME_DEFAULT,
                FLOW_PROCESSING_THREADS + ":Integer=" + FLOW_PROCESSING_THREADS_DEFAULT,
        })
public class Olt implements OltService {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceStore deviceStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected volatile SadisService sadisService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltDeviceServiceInterface oltDeviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltFlowServiceInterface oltFlowService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltMeterServiceInterface oltMeterService;

    /**
     * Default bandwidth profile id that is used for authentication trap flows.
     **/
    protected String defaultBpId = DEFAULT_BP_ID_DEFAULT;

    /**
     * Number of threads used to process flows.
     **/
    protected int flowProcessingThreads = FLOW_PROCESSING_THREADS_DEFAULT;

    /**
     * Default multicast service name.
     **/
    protected String multicastServiceName = DEFAULT_MCAST_SERVICE_NAME_DEFAULT;

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * A queue to asynchronously process events.
     */
    protected BlockingQueue<DiscoveredSubscriber> eventsQueue =
            new LinkedBlockingQueue<>();

    /**
     * Listener for OLT devices events.
     */
    private OltDeviceListener deviceListener;
    protected ScheduledExecutorService discoveredSubscriberExecutor =
            Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
                    "discovered-cp-%d", log));

    /**
     * Executor used to defer flow provisioning to a different thread pool.
     */
    private static int flowsTreads = 8;
    private ExecutorService flowsExecutor;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        deviceListener = new OltDeviceListener(clusterService, mastershipService,
                leadershipService, deviceService, oltDeviceService, oltFlowService,
                oltMeterService, eventsQueue);
        deviceService.addListener(deviceListener);
        discoveredSubscriberExecutor.execute(this::processDiscoveredSubscribers);

        flowsExecutor = Executors.newFixedThreadPool(flowProcessingThreads,
                                                     groupedThreads("onos/olt-service",
                        "flows-installer-%d"));

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        deviceService.removeListener(deviceListener);
        discoveredSubscriberExecutor.shutdown();
        flowsExecutor.shutdown();
        deviceListener.deactivate();
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

            String tpId = get(properties, FLOW_PROCESSING_THREADS);
            flowProcessingThreads = isNullOrEmpty(tpId) ?
                    FLOW_PROCESSING_THREADS_DEFAULT : Integer.parseInt(tpId.trim());

            log.debug("OLT properties: DefaultBpId: {}, MulticastServiceName: {}, flowProcessingThreads: {}",
                    defaultBpId, multicastServiceName, flowProcessingThreads);
        }
        log.info("Reconfigured");
    }


    @Override
    public boolean provisionSubscriber(ConnectPoint cp) {
        log.debug("Provisioning subscriber on {}", cp);
        Device device = deviceService.getDevice(cp.deviceId());
        Port port = deviceStore.getPort(device.id(), cp.port());
        DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                DiscoveredSubscriber.Status.ADDED, true);

        if (oltDeviceService.isNniPort(device, port)) {
            log.warn("will not provision a subscriber on the NNI");
            return false;
        }

        if (oltFlowService.isSubscriberProvisioned(cp)) {
            log.error("Subscriber on {} is already provisioned", cp);
            return false;
        }

        // NOTE we need to keep a list of the subscribers that are provisioned on a port,
        // regardless of the flow status
        oltFlowService.updateProvisionedSubscriberStatus(cp, true);

        if (!eventsQueue.contains(sub)) {
            log.info("Adding subscriber to queue: {}/{} with status {} for provisioning",
                    sub.device.id(), sub.port.number(), sub.status);
            eventsQueue.add(sub);
            return true;
        } else {
            log.debug("Subscriber queue already contains subscriber {}, " +
                              "not adding for provisioning", sub);
            return false;
        }
    }

    @Override
    public boolean removeSubscriber(ConnectPoint cp) {
        log.debug("Un-provisioning subscriber on {}", cp);
        Device device = deviceService.getDevice(DeviceId.deviceId(cp.deviceId().toString()));
        Port port = deviceStore.getPort(device.id(), cp.port());
        DiscoveredSubscriber sub = new DiscoveredSubscriber(device, port,
                DiscoveredSubscriber.Status.REMOVED, true);

        if (!oltFlowService.isSubscriberProvisioned(cp)) {
            log.error("Subscriber on {} is not provisioned", cp);
            return false;
        }

        // NOTE we need to keep a list of the subscribers that are provisioned on a port,
        // regardless of the flow status
        oltFlowService.updateProvisionedSubscriberStatus(cp, false);

        if (!eventsQueue.contains(sub)) {
            log.info("Adding subscriber to queue: {}/{} with status {} for removal",
                    sub.device.id(), sub.port.number(), sub.status);
            eventsQueue.add(sub);
            return true;
        } else {
            log.debug("Subscriber Queue already contains subscriber {}, " +
                              "not adding for removal", sub);
            return false;
        }
    }

    @Override
    public List<DeviceId> getConnectedOlts() {
        List<DeviceId> olts = new ArrayList<>();
        Iterable<Device> devices = deviceService.getDevices();
        for (Device d : devices) {
            if (oltDeviceService.isOlt(d)) {
                // So this is indeed an OLT device
                olts.add(d.id());
            }
        }
        return olts;
    }

    private void processDiscoveredSubscribers() {
        log.info("Started processDiscoveredSubscribers loop");
        while (true) {
            if (!eventsQueue.isEmpty()) {
                DiscoveredSubscriber sub = eventsQueue.poll();
                if (sub == null) {
                    // the queue is empty
                    continue;
                }
                if (log.isTraceEnabled()) {
                    log.debug("Processing subscriber on port {}/{} with status {}",
                              sub.device.id(), sub.port.number(), sub.status);
                }

                if (sub.hasSubscriber) {
                    // this is a provision subscriber call
                    flowsExecutor.execute(() -> {
                        if (!oltFlowService.handleSubscriberFlows(sub, defaultBpId)) {
                            if (log.isTraceEnabled()) {
                                log.trace("Provisioning of subscriber on {}/{} ({}) postponed",
                                          sub.device.id(), sub.port.number(), sub.portName());
                            }
                            eventsQueue.add(sub);
                        }
                    });
                } else {
                    // this is a port event (ENABLED/DISABLED)
                    // means no subscriber was provisioned on that port


                    if (!deviceService.isAvailable(sub.device.id()) ||
                            deviceService.getPort(sub.device.id(), sub.port.number()) == null) {
                        // If the device is not connected or the port is not available do nothig
                        // This can happen when we disable and then immediately delete the device,
                        // the queue is populated but the meters and flows are already gone
                        // thus there is nothing left to do
                        continue;
                    }

                    flowsExecutor.execute(() -> {
                        if (!oltFlowService.handleBasicPortFlows(sub, defaultBpId, defaultBpId)) {
                            if (log.isTraceEnabled()) {
                                log.trace("Processing of port {}/{} postponed",
                                          sub.device.id(), sub.port.number());
                            }
                            eventsQueue.add(sub);
                        }
                    });
                }
            }
        }
    }

}
