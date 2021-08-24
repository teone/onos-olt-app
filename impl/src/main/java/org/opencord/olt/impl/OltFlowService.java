package org.opencord.olt.impl;

import com.google.common.collect.ImmutableMap;
import org.onlab.packet.EthType;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.VlanId;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.Annotations;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultAnnotations;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flowobjective.DefaultFilteringObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveContext;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.host.HostService;
import org.onosproject.net.meter.MeterId;
import org.opencord.sadis.BandwidthProfileInformation;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;
import org.opencord.sadis.UniTagInformation;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.onlab.util.Tools.get;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_TP_ID;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_TP_ID_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.DOWNSTREAM_OLT;
import static org.opencord.olt.impl.OsgiPropertyConstants.DOWNSTREAM_ONU;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_ON_NNI;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_ON_NNI_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_V4;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_V4_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_V6;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_DHCP_V6_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_EAPOL;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_EAPOL_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_IGMP_ON_NNI;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_IGMP_ON_NNI_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_PPPOE;
import static org.opencord.olt.impl.OsgiPropertyConstants.ENABLE_PPPOE_DEFAULT;
import static org.opencord.olt.impl.OsgiPropertyConstants.UPSTREAM_OLT;
import static org.opencord.olt.impl.OsgiPropertyConstants.UPSTREAM_ONU;

@Component(immediate = true, property = {
        ENABLE_DHCP_ON_NNI + ":Boolean=" + ENABLE_DHCP_ON_NNI_DEFAULT,
        ENABLE_DHCP_V4 + ":Boolean=" + ENABLE_DHCP_V4_DEFAULT,
        ENABLE_DHCP_V6 + ":Boolean=" + ENABLE_DHCP_V6_DEFAULT,
        ENABLE_IGMP_ON_NNI + ":Boolean=" + ENABLE_IGMP_ON_NNI_DEFAULT,
        ENABLE_EAPOL + ":Boolean=" + ENABLE_EAPOL_DEFAULT,
        ENABLE_PPPOE + ":Boolean=" + ENABLE_PPPOE_DEFAULT,
        DEFAULT_TP_ID + ":Integer=" + DEFAULT_TP_ID_DEFAULT
})
public class OltFlowService implements OltFlowServiceInterface {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected volatile SadisService sadisService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltMeterServiceInterface oltMeterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltDeviceServiceInterface oltDeviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected DeviceService deviceService;

    protected BaseInformationService<SubscriberAndDeviceInformation> subsService;
    protected BaseInformationService<BandwidthProfileInformation> bpService;

    private static final String APP_NAME = "org.opencord.olt";
    private ApplicationId appId;
    private static final Integer MAX_PRIORITY = 10000;
    private static final Integer MIN_PRIORITY = 1000;
    private static final short EAPOL_DEFAULT_VLAN = 4091;
    private static final int NONE_TP_ID = -1;
    private static final String V4 = "V4";
    private static final String V6 = "V6";
    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * Connect Point status map.
     * Used to keep track of which cp has flows that needs to be removed when the status changes.
     */
    protected HashMap<ConnectPoint, OltPortStatus> cpStatus;
    private final ReentrantReadWriteLock cpStatusLock = new ReentrantReadWriteLock();
    private final Lock cpStatusWriteLock = cpStatusLock.writeLock();
    private final Lock cpStatusReadLock = cpStatusLock.readLock();

    /**
     * This map contains the subscriber that have been provisioned by the operator.
     * They may or may not have flows, depending on the port status.
     * The map is used to define whether flows need to be provisioned when a port comes up.
     */
    protected HashMap<ConnectPoint, Boolean> provisionedSubscribers;
    private final ReentrantReadWriteLock provisionedSubscribersLock = new ReentrantReadWriteLock();
    private final Lock provisionedSubscribersWriteLock = provisionedSubscribersLock.writeLock();
    private final Lock provisionedSubscribersReadLock = provisionedSubscribersLock.readLock();

    /**
     * Create DHCP trap flow on NNI port(s).
     */
    protected boolean enableDhcpOnNni = ENABLE_DHCP_ON_NNI_DEFAULT;

    /**
     * Enable flows for DHCP v4 if dhcp is required in sadis config.
     **/
    protected boolean enableDhcpV4 = ENABLE_DHCP_V4_DEFAULT;

    /**
     * Enable flows for DHCP v6 if dhcp is required in sadis config.
     **/
    protected boolean enableDhcpV6 = ENABLE_DHCP_V6_DEFAULT;

    /**
     * Create IGMP trap flow on NNI port(s).
     **/
    protected boolean enableIgmpOnNni = ENABLE_IGMP_ON_NNI_DEFAULT;

    /**
     * Send EAPOL authentication trap flows before subscriber provisioning.
     **/
    protected boolean enableEapol = ENABLE_EAPOL_DEFAULT;

    /**
     * Send PPPoED authentication trap flows before subscriber provisioning.
     **/
    protected boolean enablePppoe = ENABLE_PPPOE_DEFAULT;

    /**
     * Default technology profile id that is used for authentication trap flows.
     **/
    protected int defaultTechProfileId = DEFAULT_TP_ID_DEFAULT;

    public enum FlowAction {
        ADD,
        REMOVE,
    }

    public enum FlowDirection {
        UPSTREAM,
        DOWNSTREAM,
    }

    @Activate
    public void activate() {
        bpService = sadisService.getBandwidthProfileService();
        subsService = sadisService.getSubscriberInfoService();
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication(APP_NAME);

        // TODO this should be a distributed map
        // NOTE this maps is lost on app/node restart, can we rebuild it?
        cpStatus = new HashMap<>();
        provisionedSubscribers = new HashMap<>();

        log.info("Activated");
    }

    @Deactivate
    public void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
    }

    @Modified
    public void modified(ComponentContext context) {

        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();

        Boolean o = Tools.isPropertyEnabled(properties, ENABLE_DHCP_ON_NNI);
        if (o != null) {
            enableDhcpOnNni = o;
        }

        Boolean v4 = Tools.isPropertyEnabled(properties, ENABLE_DHCP_V4);
        if (v4 != null) {
            enableDhcpV4 = v4;
        }

        Boolean v6 = Tools.isPropertyEnabled(properties, ENABLE_DHCP_V6);
        if (v6 != null) {
            enableDhcpV6 = v6;
        }

        Boolean p = Tools.isPropertyEnabled(properties, ENABLE_IGMP_ON_NNI);
        if (p != null) {
            enableIgmpOnNni = p;
        }

        Boolean eap = Tools.isPropertyEnabled(properties, ENABLE_EAPOL);
        if (eap != null) {
            enableEapol = eap;
        }

        Boolean pppoe = Tools.isPropertyEnabled(properties, ENABLE_PPPOE);
        if (pppoe != null) {
            enablePppoe = pppoe;
        }

        String tpId = get(properties, DEFAULT_TP_ID);
        defaultTechProfileId = isNullOrEmpty(tpId) ? DEFAULT_TP_ID_DEFAULT : Integer.parseInt(tpId.trim());

        log.info("modified. Values = enableDhcpOnNni: {}, enableDhcpV4: {}, " +
                        "enableDhcpV6:{}, enableIgmpOnNni:{}, " +
                        "enableEapol:{}, enablePppoe:{}, defaultTechProfileId:{}",
                enableDhcpOnNni, enableDhcpV4, enableDhcpV6,
                enableIgmpOnNni, enableEapol, enablePppoe,
                defaultTechProfileId);

    }

    @Override
    public Map<ConnectPoint, OltPortStatus> getConnectPointStatus() {
        try {
            cpStatusReadLock.lock();
            return ImmutableMap.copyOf(cpStatus);
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public ImmutableMap<ConnectPoint, Set<UniTagInformation>> getProgrammedSusbcribers() {

        Map<ConnectPoint, Set<UniTagInformation>> subscribers =
                new HashMap<>();
        try {
            cpStatusReadLock.lock();
            cpStatus.forEach((cp, status) -> {
                Set<UniTagInformation> uniTags = subscribers.getOrDefault(cp, new HashSet<>());
                if (hasSubscriberFlows(cp.deviceId(), cp.port())) {
                    Port port = deviceService.getPort(cp);
                    if (port == null) {
                        log.warn("Cannot find port {} on device {}", cp.port(), cp.deviceId());
                        return;
                    }
                    String portName = deviceService.getPort(cp).annotations().value(AnnotationKeys.PORT_NAME);
                    SubscriberAndDeviceInformation info = subsService.get(portName);
                    if (info == null) {
                        log.error("Cannot find information for port {}/{}", cp.deviceId(), cp.port());
                        return;
                    }
                    info.uniTagList().forEach(i -> {
                        uniTags.add(i);
                    });
                }
                subscribers.put(cp, uniTags);
            });
            return ImmutableMap.copyOf(subscribers);
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public void handleNniFlows(Device device, Port port, FlowAction action) {

        // always handle the LLDP flow
        processLldpFilteringObjective(device.id(), port, action);

        if (enableDhcpOnNni) {
            if (enableDhcpV4) {
                log.debug("Installing DHCPv4 trap flow on NNI {} for device {}", port.number(), device.id());
                processDhcpFilteringObjectives(device.id(), port, action, FlowDirection.DOWNSTREAM,
                        67, 68, EthType.EtherType.IPV4.ethType(), IPv4.PROTOCOL_UDP,
                        null, null, NONE_TP_ID, VlanId.NONE, VlanId.ANY, null);
            }
            if (enableDhcpV6) {
                log.debug("Installing DHCPv6 trap flow on NNI {} for device {}", port.number(), device.id());
                processDhcpFilteringObjectives(device.id(), port, action, FlowDirection.DOWNSTREAM,
                        546, 547, EthType.EtherType.IPV6.ethType(), IPv6.PROTOCOL_UDP,
                        null, null, NONE_TP_ID, VlanId.NONE, VlanId.ANY, null);
            }
        } else {
            log.info("DHCP is not required on NNI {} for device {}", port.number(), device.id());
        }

        if (enableIgmpOnNni) {
            log.debug("Installing IGMP flow on NNI {} for device {}", port.number(), device.id());
            processIgmpFilteringObjectives(device.id(), port, action, FlowDirection.DOWNSTREAM,
                    null, null, NONE_TP_ID, VlanId.NONE, VlanId.ANY, null);
        }

        if (enablePppoe) {
            log.debug("Installing PPPoE flow on NNI {} for device {}", port.number(), device.id());
            processPPPoEDFilteringObjectives(device.id(), port, action, FlowDirection.DOWNSTREAM,
                    null, null, NONE_TP_ID, VlanId.NONE, VlanId.ANY, null);
        }
    }

    @Override
    public void handleBasicPortFlows(DiscoveredSubscriber sub, String bandwidthProfile, String oltBandwidthProfile)
            throws Exception {

        // we only need to something if EAPOL is enabled
        if (!enableEapol) {
            return;
        }

        if (sub.status == DiscoveredSubscriber.Status.ADDED) {
            addDefaultFlows(sub, bandwidthProfile, oltBandwidthProfile);
        } else if (sub.status == DiscoveredSubscriber.Status.REMOVED) {
            removeDefaultFlows(sub, bandwidthProfile, oltBandwidthProfile);
        }

    }

    private void addDefaultFlows(DiscoveredSubscriber sub, String bandwidthProfile, String oltBandwidthProfile)
            throws Exception {
        oltMeterService.createMeter(sub.device.id(), bandwidthProfile);
        handleEapolFlow(sub, bandwidthProfile, oltBandwidthProfile, FlowAction.ADD, VlanId.vlanId(EAPOL_DEFAULT_VLAN));

        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
        updateConnectPointStatus(cp, OltFlowsStatus.PENDING_ADD, OltFlowsStatus.NONE, OltFlowsStatus.NONE);
    }

    private void removeDefaultFlows(DiscoveredSubscriber sub, String bandwidthProfile, String oltBandwidthProfile)
            throws Exception {

        // NOTE that we are not checking for meters as they must have been created to install the flow in first place
        handleEapolFlow(sub, bandwidthProfile, oltBandwidthProfile,
                FlowAction.REMOVE, VlanId.vlanId(EAPOL_DEFAULT_VLAN));
        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
        updateConnectPointStatus(cp, OltFlowsStatus.PENDING_REMOVE, null, null);
    }

    @Override
    public void handleSubscriberFlows(DiscoveredSubscriber sub, String defaultBandwithProfile) throws Exception {
        // NOTE that we are taking defaultBandwithProfile as a parameter as that can be configured in the Olt component

        if (sub.status == DiscoveredSubscriber.Status.ADDED) {
            addSubscriberFlows(sub, defaultBandwithProfile);
        } else if (sub.status == DiscoveredSubscriber.Status.REMOVED) {
            removeSubscriberFlows(sub, defaultBandwithProfile);
        }
    }

    private void addSubscriberFlows(DiscoveredSubscriber sub, String defaultBandwithProfile) throws Exception {
        if (log.isTraceEnabled()) {
            log.trace("Provisioning of subscriber on {}/{} ({}) started",
                    sub.device.id(), sub.port.number(), sub.portName());
        }
        if (enableEapol) {
            if (hasDefaultEapol(sub.device.id(), sub.port.number())) {
                // remove EAPOL flow and throw exception so that we'll retry later
                removeDefaultFlows(sub, defaultBandwithProfile, defaultBandwithProfile);
                throw new Exception(String.format("Awaiting for default flows removal for %s/%s (%s)",
                        sub.device.id(), sub.port.number(), sub.portName()));
            }
        }

        SubscriberAndDeviceInformation si = subsService.get(sub.portName());
        if (si == null) {
            log.error("Subscriber information not found in sadis for port {}/{} ({})",
                    sub.device.id(), sub.port.number(), sub.portName());
            // NOTE that we are not throwing an exception so that the subscriber is removed from the queue
            // and we can move on provisioning others
            return;
        }

        // NOTE createMeters will throw if the meters are not ready
        oltMeterService.createMeters(sub.device.id(), si);

        // NOTE we need to add the DHCP flow regardless so that the host can be discovered and the MacAddress added
        handleSubscriberDhcpFlows(sub.device.id(), sub.port, FlowAction.ADD, si);

        if (isMacLearningEnabled(si) && !isMacAddressAvailable(sub.device.id(), sub.port, si)) {
            throw new Exception(String.format("Awaiting for macAddress on %s/%s (%s)",
                    sub.device.id(), sub.port.number(), sub.portName()));
        }

        // NOTE do we need to do anything for IGMP?
        handleSubscriberDataFlows(sub.device, sub.port, FlowAction.ADD, si);

        handleSubscriberEapolFlows(sub, FlowAction.ADD, si);

        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());


        log.info("Provisioning of subscriber on {}/{} ({}) completed",
                sub.device.id(), sub.port.number(), sub.portName());
    }

    private void removeSubscriberFlows(DiscoveredSubscriber sub, String defaultBandwithProfile) throws Exception {

        if (log.isTraceEnabled()) {
            log.info("Removal of subscriber on {}/{} ({}) started",
                    sub.device.id(), sub.port.number(), sub.portName());
        }
        SubscriberAndDeviceInformation si = subsService.get(sub.portName());
        if (si == null) {
            log.error("Subscriber information not found in sadis for port {}/{} ({}) during subscriber removal",
                    sub.device.id(), sub.port.number(), sub.portName());
            // NOTE that we are not throwing an exception so that the subscriber is removed from the queue
            // and we can move on provisioning others
            return;
        }

        // NOTE why don't we simply remove all the flows on this port?

        if (hasDhcpFlows(sub.device.id(), sub.port.number())) {
            handleSubscriberDhcpFlows(sub.device.id(), sub.port, FlowAction.REMOVE, si);
        }

        if (hasSubscriberFlows(sub.device.id(), sub.port.number())) {
            if (enableEapol) {
                // remove the tagged eapol
                handleSubscriberEapolFlows(sub, FlowAction.REMOVE, si);

                // and add the default one back
                if (sub.port.isEnabled()) {
                    // NOTE we remove the subscriber when the port goes down
                    // but in that case we don't need to add default eapol
                    handleEapolFlow(sub, defaultBandwithProfile, defaultBandwithProfile,
                            FlowAction.ADD, VlanId.vlanId(EAPOL_DEFAULT_VLAN));
                }
            }
            handleSubscriberDataFlows(sub.device, sub.port, FlowAction.REMOVE, si);
        }

        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());

        log.info("Removal of subscriber on {}/{} ({}) completed",
                sub.device.id(), sub.port.number(), sub.portName());
    }

    @Override
    public boolean hasDefaultEapol(DeviceId deviceId, PortNumber portNumber) {
        try {
            cpStatusReadLock.lock();
            ConnectPoint cp = new ConnectPoint(deviceId, portNumber);
            OltPortStatus status = cpStatus.get(cp);
            if (status == null) {
                return false;
            }
            return status.defaultEapolStatus == OltFlowsStatus.ADDED ||
                    status.defaultEapolStatus == OltFlowsStatus.PENDING_ADD;
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public boolean hasDhcpFlows(DeviceId deviceId, PortNumber portNumber) {
        try {
            cpStatusReadLock.lock();
            ConnectPoint cp = new ConnectPoint(deviceId, portNumber);
            OltPortStatus status = cpStatus.get(cp);
            if (status == null) {
                return false;
            }
            return status.dhcpStatus == OltFlowsStatus.ADDED || status.dhcpStatus == OltFlowsStatus.PENDING_ADD;
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public boolean hasSubscriberFlows(DeviceId deviceId, PortNumber portNumber) {
        try {
            cpStatusReadLock.lock();
            ConnectPoint cp = new ConnectPoint(deviceId, portNumber);
            OltPortStatus status = cpStatus.get(cp);
            if (status == null) {
                return false;
            }

            return status.subscriberFlowsStatus == OltFlowsStatus.ADDED ||
                    status.subscriberFlowsStatus == OltFlowsStatus.PENDING_ADD;
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public void purgeDeviceFlows(DeviceId deviceId) {
        log.debug("Purging flows on device {}", deviceId);
        flowRuleService.purgeFlowRules(deviceId);

        // removing the status from the cpStatus map
        try {
            cpStatusWriteLock.lock();
            cpStatus.entrySet().removeIf(i -> i.getKey().deviceId().equals(deviceId));
        } finally {
            cpStatusWriteLock.unlock();
        }

        // removing subscribers from the provisioned map
        try {
            provisionedSubscribersWriteLock.lock();
            provisionedSubscribers.entrySet().removeIf(i -> i.getKey().deviceId().equals(deviceId));
        } finally {
            provisionedSubscribersWriteLock.unlock();
        }
    }

    @Override
    public Boolean isSubscriberProvisioned(ConnectPoint cp) {
        try {
            provisionedSubscribersReadLock.lock();
            Boolean provisioned = provisionedSubscribers.get(cp);
            if (provisioned == null || !provisioned) {
                return false;
            }
        } finally {
            provisionedSubscribersReadLock.unlock();
        }
        return true;
    }

    @Override
    public void updateProvisionedSubscriberStatus(ConnectPoint cp, Boolean status) {
        try {
            provisionedSubscribersWriteLock.lock();
            provisionedSubscribers.put(cp, status);
        } finally {
            provisionedSubscribersWriteLock.unlock();
        }
    }

    // NOTE this method can most likely be generalized to:
    // - handle EAPOL flows with customer VLANs
    private void handleEapolFlow(DiscoveredSubscriber sub, String bandwidthProfile,
                                 String oltBandwidthProfile, FlowAction action, VlanId vlanId)
            throws Exception {

        DefaultFilteringObjective.Builder filterBuilder = DefaultFilteringObjective.builder();
        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();

        int techProfileId = getDefaultTechProfileId(sub.port);
        MeterId meterId = oltMeterService.getMeterIdForBandwidthProfile(sub.device.id(), bandwidthProfile);

        // in the delete case the meter should still be there as we remove
        // the meters only if no flows are pointing to them
        if (meterId == null) {
            throw new Exception(String.format("MeterId is null for BandwidthProfile %s on device %s",
                    bandwidthProfile, sub.device.id()));
        }

        MeterId oltMeterId = oltMeterService.getMeterIdForBandwidthProfile(sub.device.id(), oltBandwidthProfile);
        if (oltMeterId == null) {
            throw new Exception(String.format("MeterId is null for OltBandwidthProfile %s on device %s",
                    oltBandwidthProfile, sub.device.id()));
        }

        log.info("Preforming {} on EAPOL flow for {}/{} with vlanId {} and meterId {}",
                action, sub.device.id(), sub.port.number(), vlanId, meterId);

        FilteringObjective.Builder eapolAction;

        if (action == FlowAction.ADD) {
            eapolAction = filterBuilder.permit();
        } else if (action == FlowAction.REMOVE) {
            eapolAction = filterBuilder.deny();
        } else {
            throw new Exception(String.format("Operation %s not supported", action));
        }

        FilteringObjective.Builder baseEapol = eapolAction
                .withKey(Criteria.matchInPort(sub.port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.EAPOL.ethType()));

        // NOTE we only need to add the treatment to install the flow,
        // we can remove it based in the match
        FilteringObjective.Builder eapol;

        TrafficTreatment treatment = treatmentBuilder
                .meter(meterId)
                .writeMetadata(createTechProfValueForWm(
                        vlanId,
                        techProfileId, oltMeterId), 0)
                .setOutput(PortNumber.CONTROLLER)
                .pushVlan()
                .setVlanId(vlanId)
                .build();
        eapol = baseEapol
                .withMeta(treatment);

        FilteringObjective eapolObjective = eapol
                .fromApp(appId)
                .withPriority(MAX_PRIORITY)
                .add(new ObjectiveContext() {
                    @Override
                    public void onSuccess(Objective objective) {
                        log.info("EAPOL flow {} for {}/{} with details {}",
                                action, sub.device.id(), sub.port.number(), objective);

                        // update the flow status in cpStatus map
                        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
                        OltFlowsStatus status = action.equals(FlowAction.ADD) ?
                                OltFlowsStatus.ADDED : OltFlowsStatus.REMOVED;
                        // NOTE it may be worthy to look into updating the cpStatusMap
                        // based on flow events instead of flowObjective results
                        // downside: may be much slower
                        if (vlanId.id().equals(EAPOL_DEFAULT_VLAN)) {
                            // NOTE we only care about EAPOL status with the default VLAN,
                            // a tagged EAPOL flow falls in the subscriber flows category
                            updateConnectPointStatus(cp, status, null, null);
                        }
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.error("Cannot {} eapol flow: {}", action, error);
                        ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
                        if (vlanId.id().equals(EAPOL_DEFAULT_VLAN)) {
                            updateConnectPointStatus(cp, OltFlowsStatus.ERROR, null, null);
                        }
                    }
                });

        log.info("Created EAPOL filter to {} for {}/{}: {}", action, sub.device.id(), sub.port.number(), eapol);

        flowObjectiveService.filter(sub.device.id(), eapolObjective);
    }

    private void handleSubscriberEapolFlows(DiscoveredSubscriber sub, FlowAction action,
                                            SubscriberAndDeviceInformation si) {
        if (!enableEapol) {
            return;
        }
        // TODO verify we need an EAPOL flow for EACH service
        si.uniTagList().forEach(u -> {
            try {
                handleEapolFlow(sub, u.getUpstreamBandwidthProfile(), u.getUpstreamOltBandwidthProfile(),
                        action, u.getPonCTag());
            } catch (Exception e) {
                log.error("Failed to install EAPOL with suscriber tags");
            }
        });

    }

    private boolean checkSadisRunning() {
        if (bpService == null) {
            log.warn("Sadis is not running");
            return false;
        }
        return true;
    }

    private int getDefaultTechProfileId(Port port) {
        if (!checkSadisRunning()) {
            return defaultTechProfileId;
        }
        if (port != null) {
            SubscriberAndDeviceInformation info = subsService.get(port.annotations().value(AnnotationKeys.PORT_NAME));
            if (info != null && info.uniTagList().size() == 1) {
                return info.uniTagList().get(0).getTechnologyProfileId();
            }
        }
        return defaultTechProfileId;
    }

    protected Long createTechProfValueForWm(VlanId cVlan, int techProfileId, MeterId upstreamOltMeterId) {
        Long writeMetadata;

        if (cVlan == null || VlanId.NONE.equals(cVlan)) {
            writeMetadata = (long) techProfileId << 32;
        } else {
            writeMetadata = ((long) (cVlan.id()) << 48 | (long) techProfileId << 32);
        }
        if (upstreamOltMeterId == null) {
            return writeMetadata;
        } else {
            return writeMetadata | upstreamOltMeterId.id();
        }
    }

    private void processLldpFilteringObjective(DeviceId deviceId, Port port, FlowAction action) {
        DefaultFilteringObjective.Builder builder = DefaultFilteringObjective.builder();

        FilteringObjective lldp = (action == FlowAction.ADD ? builder.permit() : builder.deny())
                .withKey(Criteria.matchInPort(port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.LLDP.ethType()))
                .withMeta(DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.CONTROLLER).build())
                .fromApp(appId)
                .withPriority(MAX_PRIORITY)
                .add(new ObjectiveContext() {
                    @Override
                    public void onSuccess(Objective objective) {
                        log.info("LLDP filter for {} {}.", port, action);
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.error("LLDP filter for {} failed {} because {}", port, action,
                                error);
                    }
                });

        flowObjectiveService.filter(deviceId, lldp);
    }

    protected void handleSubscriberDhcpFlows(DeviceId deviceId, Port port,
                                             FlowAction action,
                                             SubscriberAndDeviceInformation si) {
        si.uniTagList().forEach(uti -> {

            if (!uti.getIsDhcpRequired()) {
                return;
            }

            log.info("{} DHCP flows for subscriber {} on {}/{} and service {}",
                    action, si.id(), deviceId, port.number(), uti.getServiceName());

            // if we reached here a meter already exists
            MeterId meterId = oltMeterService
                    .getMeterIdForBandwidthProfile(deviceId, uti.getUpstreamBandwidthProfile());
            MeterId oltMeterId = oltMeterService
                    .getMeterIdForBandwidthProfile(deviceId, uti.getUpstreamOltBandwidthProfile());

            if (enableDhcpV4) {
                processDhcpFilteringObjectives(deviceId, port, action, FlowDirection.UPSTREAM, 68, 67,
                        EthType.EtherType.IPV4.ethType(), IPv4.PROTOCOL_UDP, meterId, oltMeterId,
                        uti.getTechnologyProfileId(), uti.getPonCTag(), uti.getUniTagMatch(),
                        (byte) uti.getUsPonCTagPriority());
            }
            if (enableDhcpV6) {
                log.error("DHCP V6 not supported for subscribers");
            }
        });
    }

    protected void handleSubscriberDataFlows(Device device, Port port,
                                             FlowAction action,
                                             SubscriberAndDeviceInformation si) {

        Optional<Port> nniPort = oltDeviceService.getNniPort(device);
        if (nniPort.isEmpty()) {
            log.error("Cannot configure DP flows as upstream port is not configuredfor subscriber {} on {}/{}",
                    si.id(), device.id(), port.number());
            return;
        }
        si.uniTagList().forEach(uti -> {
            log.info("{} Data plane flows for subscriber {} on {}/{} and service {}",
                    action, si.id(), device.id(), port.number(), uti.getServiceName());

            // upstream flows
            MeterId usMeterId = oltMeterService
                    .getMeterIdForBandwidthProfile(device.id(), uti.getUpstreamBandwidthProfile());
            MeterId oltUsMeterId = oltMeterService
                    .getMeterIdForBandwidthProfile(device.id(), uti.getUpstreamOltBandwidthProfile());
            processUpstreamDataFilteringObjects(device.id(), port, nniPort.get(), action, usMeterId,
                    oltUsMeterId, uti.getTechnologyProfileId(), uti.getPonSTag(), uti.getPonCTag(),
                    uti.getUniTagMatch(), (byte) uti.getUsPonCTagPriority());

            // downstream flows
            MeterId dsMeterId = oltMeterService
                    .getMeterIdForBandwidthProfile(device.id(), uti.getDownstreamBandwidthProfile());
            MeterId oltDsMeterId = oltMeterService
                    .getMeterIdForBandwidthProfile(device.id(), uti.getDownstreamOltBandwidthProfile());
            processDownstreamDataFilteringObjects(device.id(), port, nniPort.get(), action, dsMeterId,
                    oltDsMeterId, uti.getTechnologyProfileId(), uti.getPonSTag(), uti.getPonCTag(),
                    uti.getUniTagMatch(), (byte) uti.getDsPonCTagPriority(), (byte) uti.getUsPonCTagPriority(),
                    getMacAddress(device.id(), port, uti));
        });
    }

    private void processDhcpFilteringObjectives(DeviceId deviceId, Port port,
                                                FlowAction action, FlowDirection direction,
                                                int udpSrc, int udpDst, EthType ethType, byte protocol,
                                                MeterId meterId, MeterId oltMeterId, int techProfileId,
                                                VlanId cTag, VlanId unitagMatch, Byte vlanPcp) {
        log.debug("{} DHCP filtering objectives on {}/{}", action, deviceId, port.number());

        ConnectPoint cp = new ConnectPoint(deviceId, port.number());
        OltFlowsStatus status = action.equals(FlowAction.ADD) ?
                OltFlowsStatus.PENDING_ADD : OltFlowsStatus.PENDING_REMOVE;
        updateConnectPointStatus(cp, null, null, status);

        DefaultFilteringObjective.Builder builder = DefaultFilteringObjective.builder();
        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();

        if (meterId != null) {
            treatmentBuilder.meter(meterId);
        }

        if (techProfileId != NONE_TP_ID) {
            treatmentBuilder.writeMetadata(createTechProfValueForWm(unitagMatch, techProfileId, oltMeterId), 0);
        }

        FilteringObjective.Builder dhcpBuilder = (action == FlowAction.ADD ? builder.permit() : builder.deny())
                .withKey(Criteria.matchInPort(port.number()))
                .addCondition(Criteria.matchEthType(ethType))
                .addCondition(Criteria.matchIPProtocol(protocol))
                .addCondition(Criteria.matchUdpSrc(TpPort.tpPort(udpSrc)))
                .addCondition(Criteria.matchUdpDst(TpPort.tpPort(udpDst)))
                .fromApp(appId)
                .withPriority(MAX_PRIORITY);

        //VLAN changes and PCP matching need to happen only in the upstream directions
        if (direction == FlowDirection.UPSTREAM) {
            treatmentBuilder.setVlanId(cTag);
            if (!VlanId.vlanId(VlanId.NO_VID).equals(unitagMatch)) {
                dhcpBuilder.addCondition(Criteria.matchVlanId(unitagMatch));
            }
            if (vlanPcp != null) {
                treatmentBuilder.setVlanPcp(vlanPcp);
            }
        }

        dhcpBuilder.withMeta(treatmentBuilder
                .setOutput(PortNumber.CONTROLLER).build());


        FilteringObjective dhcpUpstream = dhcpBuilder.add(new ObjectiveContext() {
            @Override
            public void onSuccess(Objective objective) {
                log.info("DHCP {} filter for {} {}.",
                        (ethType.equals(EthType.EtherType.IPV4.ethType())) ? V4 : V6, port,
                        action);

                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                OltFlowsStatus status = action.equals(FlowAction.ADD) ?
                        OltFlowsStatus.ADDED : OltFlowsStatus.REMOVED;
                updateConnectPointStatus(cp, null, null, status);
            }

            @Override
            public void onError(Objective objective, ObjectiveError error) {
                log.error("DHCP {} filter for {} failed {} because {}",
                        (ethType.equals(EthType.EtherType.IPV4.ethType())) ? V4 : V6, port,
                        action,
                        error);
                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                updateConnectPointStatus(cp, null, null, OltFlowsStatus.ERROR);
            }
        });
        flowObjectiveService.filter(deviceId, dhcpUpstream);
    }

    private void processIgmpFilteringObjectives(DeviceId deviceId, Port port,
                                                FlowAction action, FlowDirection direction,
                                                MeterId meterId, MeterId oltMeterId, int techProfileId,
                                                VlanId cTag, VlanId unitagMatch, Byte vlanPcp) {

        DefaultFilteringObjective.Builder filterBuilder = DefaultFilteringObjective.builder();
        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();
        if (direction == FlowDirection.UPSTREAM) {

            if (techProfileId != NONE_TP_ID) {
                treatmentBuilder.writeMetadata(createTechProfValueForWm(null,
                        techProfileId, oltMeterId), 0);
            }


            if (meterId != null) {
                treatmentBuilder.meter(meterId);
            }

            if (!VlanId.vlanId(VlanId.NO_VID).equals(unitagMatch)) {
                filterBuilder.addCondition(Criteria.matchVlanId(unitagMatch));
            }

            if (!VlanId.vlanId(VlanId.NO_VID).equals(cTag)) {
                treatmentBuilder.setVlanId(cTag);
            }

            if (vlanPcp != null) {
                treatmentBuilder.setVlanPcp(vlanPcp);
            }
        }

        filterBuilder = (action == FlowAction.ADD) ? filterBuilder.permit() : filterBuilder.deny();

        FilteringObjective igmp = filterBuilder
                .withKey(Criteria.matchInPort(port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.IPV4.ethType()))
                .addCondition(Criteria.matchIPProtocol(IPv4.PROTOCOL_IGMP))
                .withMeta(treatmentBuilder
                        .setOutput(PortNumber.CONTROLLER).build())
                .fromApp(appId)
                .withPriority(MAX_PRIORITY)
                .add(new ObjectiveContext() {
                    @Override
                    public void onSuccess(Objective objective) {
                        log.info("Igmp filter for {} {}.", port, action);
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.error("Igmp filter for {} failed {} because {}.", port, action,
                                error);
                    }
                });

        flowObjectiveService.filter(deviceId, igmp);

    }

    private void processPPPoEDFilteringObjectives(DeviceId deviceId, Port port,
                                                  FlowAction action, FlowDirection direction,
                                                  MeterId meterId, MeterId oltMeterId, int techProfileId,
                                                  VlanId cTag, VlanId unitagMatch, Byte vlanPcp) {

        DefaultFilteringObjective.Builder builder = DefaultFilteringObjective.builder();
        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();

        if (meterId != null) {
            treatmentBuilder.meter(meterId);
        }

        if (techProfileId != NONE_TP_ID) {
            treatmentBuilder.writeMetadata(createTechProfValueForWm(cTag, techProfileId, oltMeterId), 0);
        }

        DefaultFilteringObjective.Builder pppoedBuilder = ((action == FlowAction.ADD)
                ? builder.permit() : builder.deny())
                .withKey(Criteria.matchInPort(port.number()))
                .addCondition(Criteria.matchEthType(EthType.EtherType.PPPoED.ethType()))
                .fromApp(appId)
                .withPriority(10000);

        if (direction == FlowDirection.UPSTREAM) {
            treatmentBuilder.setVlanId(cTag);
            if (!VlanId.vlanId(VlanId.NO_VID).equals(unitagMatch)) {
                pppoedBuilder.addCondition(Criteria.matchVlanId(unitagMatch));
            }
            if (vlanPcp != null) {
                treatmentBuilder.setVlanPcp(vlanPcp);
            }
        }
        pppoedBuilder = pppoedBuilder.withMeta(treatmentBuilder.setOutput(PortNumber.CONTROLLER).build());

        FilteringObjective pppoed = pppoedBuilder
                .add(new ObjectiveContext() {
                    @Override
                    public void onSuccess(Objective objective) {
                        log.info("PPPoED filter for {} {}.", port, action);
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.info("PPPoED filter for {} failed {} because {}", port,
                                action, error);
                    }
                });
        flowObjectiveService.filter(deviceId, pppoed);
    }

    private void processUpstreamDataFilteringObjects(DeviceId deviceId, Port port, Port nniPort,
                                                     FlowAction action,
                                                     MeterId upstreamMeterId,
                                                     MeterId upstreamOltMeterId,
                                                     int techProfileId, VlanId sTag,
                                                     VlanId cTag, VlanId unitagMatch, Byte vlanPcp) {

        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchInPort(port.number())
                .matchVlanId(unitagMatch)
                .build();

        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();
        //if the subscriberVlan (cTag) is different than ANY it needs to set.
        if (cTag.toShort() != VlanId.ANY_VALUE) {
            treatmentBuilder.pushVlan()
                    .setVlanId(cTag);
        }

        if (vlanPcp != null) {
            treatmentBuilder.setVlanPcp(vlanPcp);
        }

        treatmentBuilder.pushVlan()
                .setVlanId(sTag);

        if (vlanPcp != null) {
            treatmentBuilder.setVlanPcp(vlanPcp);
        }

        treatmentBuilder.setOutput(nniPort.number())
                .writeMetadata(createMetadata(cTag,
                        techProfileId, nniPort.number()), 0L);

        DefaultAnnotations.Builder annotationBuilder = DefaultAnnotations.builder();

        if (upstreamMeterId != null) {
            treatmentBuilder.meter(upstreamMeterId);
            annotationBuilder.set(UPSTREAM_ONU, upstreamMeterId.toString());
        }
        if (upstreamOltMeterId != null) {
            treatmentBuilder.meter(upstreamOltMeterId);
            annotationBuilder.set(UPSTREAM_OLT, upstreamOltMeterId.toString());
        }

        DefaultForwardingObjective.Builder flowBuilder = createForwardingObjectiveBuilder(selector,
                treatmentBuilder.build(), MIN_PRIORITY,
                annotationBuilder.build());

        ObjectiveContext context = new ObjectiveContext() {
            @Override
            public void onSuccess(Objective objective) {
                log.info("Upstream Data plane filter for {} {}.", port, action);

                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                OltFlowsStatus status = action.equals(FlowAction.ADD) ?
                        OltFlowsStatus.ADDED : OltFlowsStatus.REMOVED;
                updateConnectPointStatus(cp, null, status, null);
            }

            @Override
            public void onError(Objective objective, ObjectiveError error) {
                log.info("Upstream Data plane filter for {} failed {} because {}.", port, action, error);
                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                updateConnectPointStatus(cp, null, OltFlowsStatus.ERROR, null);
            }
        };

        ForwardingObjective flow = null;
        if (action == FlowAction.ADD) {
            flow = flowBuilder.add(context);
        } else if (action == FlowAction.REMOVE) {
            flow = flowBuilder.remove(context);
        } else {
            log.error("Flow action not supported: {}", action);
        }

        if (flow != null) {
            flowObjectiveService.forward(deviceId, flow);
        }
    }

    private void processDownstreamDataFilteringObjects(DeviceId deviceId, Port port, Port nniPort,
                                                       FlowAction action,
                                                       MeterId downstreamMeterId,
                                                       MeterId downstreamOltMeterId,
                                                       int techProfileId, VlanId sTag,
                                                       VlanId cTag, VlanId unitagMatch, Byte vlanPcpDs, Byte vlanPcpUs,
                                                       MacAddress macAddress) {
        //subscriberVlan can be any valid Vlan here including ANY to make sure the packet is tagged
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder()
                .matchVlanId(sTag)
                .matchInPort(nniPort.number())
                .matchInnerVlanId(cTag);


        if (cTag.toShort() != VlanId.ANY_VALUE) {
            selectorBuilder.matchMetadata(cTag.toShort());
        }

        if (vlanPcpDs != null) {
            selectorBuilder.matchVlanPcp(vlanPcpDs);
        }

        if (macAddress != null) {
            selectorBuilder.matchEthDst(macAddress);
        }

        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder()
                .popVlan()
                .setOutput(port.number());

        treatmentBuilder.writeMetadata(createMetadata(cTag,
                techProfileId,
                port.number()), 0);

        // Upstream pbit is used to remark inner vlan pbit.
        // Upstream is used to avoid trusting the BNG to send the packet with correct pbit.
        // this is done because ds mode 0 is used because ds mode 3 or 6 that allow for
        // all pbit acceptance are not widely supported by vendors even though present in
        // the OMCI spec.
        if (vlanPcpUs != null) {
            treatmentBuilder.setVlanPcp((byte) vlanPcpUs);
        }

        if (!VlanId.NONE.equals(unitagMatch) &&
                cTag.toShort() != VlanId.ANY_VALUE) {
            treatmentBuilder.setVlanId(unitagMatch);
        }

        DefaultAnnotations.Builder annotationBuilder = DefaultAnnotations.builder();

        if (downstreamMeterId != null) {
            treatmentBuilder.meter(downstreamMeterId);
            annotationBuilder.set(DOWNSTREAM_ONU, downstreamMeterId.toString());
        }

        if (downstreamOltMeterId != null) {
            treatmentBuilder.meter(downstreamOltMeterId);
            annotationBuilder.set(DOWNSTREAM_OLT, downstreamOltMeterId.toString());
        }

        DefaultForwardingObjective.Builder flowBuilder  = createForwardingObjectiveBuilder(selectorBuilder.build(),
                treatmentBuilder.build(), MIN_PRIORITY, annotationBuilder.build());

        ObjectiveContext context = new ObjectiveContext() {
            @Override
            public void onSuccess(Objective objective) {
                log.info("Downstream Data plane filter for {} {}.", port, action);

                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                OltFlowsStatus status = action.equals(FlowAction.ADD) ?
                        OltFlowsStatus.ADDED : OltFlowsStatus.REMOVED;
                updateConnectPointStatus(cp, null, status, null);
            }

            @Override
            public void onError(Objective objective, ObjectiveError error) {
                log.info("Downstream Data plane filter for {} failed {} because {}.", port, action, error);
                ConnectPoint cp = new ConnectPoint(deviceId, port.number());
                updateConnectPointStatus(cp, null, OltFlowsStatus.ERROR, null);
            }
        };

        ForwardingObjective flow = null;
        if (action == FlowAction.ADD) {
            flow = flowBuilder.add(context);
        } else if (action == FlowAction.REMOVE) {
            flow = flowBuilder.remove(context);
        } else {
            log.error("Flow action not supported: {}", action);
        }

        if (flow != null) {
            flowObjectiveService.forward(deviceId, flow);
        }
    }

    private DefaultForwardingObjective.Builder createForwardingObjectiveBuilder(TrafficSelector selector,
                                                                                TrafficTreatment treatment,
                                                                                Integer priority,
                                                                                Annotations annotations) {
        return DefaultForwardingObjective.builder()
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .withPriority(priority)
                .makePermanent()
                .withSelector(selector)
                .withAnnotations(annotations)
                .fromApp(appId)
                .withTreatment(treatment);
    }

    private Long createMetadata(VlanId innerVlan, int techProfileId, PortNumber egressPort) {
        if (techProfileId == NONE_TP_ID) {
            techProfileId = DEFAULT_TP_ID_DEFAULT;
        }

        return ((long) (innerVlan.id()) << 48 | (long) techProfileId << 32) | egressPort.toLong();
    }

    private boolean isMacLearningEnabled(SubscriberAndDeviceInformation si) {
        AtomicBoolean requiresMacLearning = new AtomicBoolean();
        requiresMacLearning.set(false);

        si.uniTagList().stream().forEach(uniTagInfo -> {
            if (uniTagInfo.getEnableMacLearning()) {
                requiresMacLearning.set(true);
            }
        });

        return requiresMacLearning.get();
    }

    /**
     * Checks whether the subscriber has the MacAddress configured or discovered.
     *
     * @param deviceId DeviceId for this subscriber
     * @param port     Port for this subscriber
     * @param si       SubscriberAndDeviceInformation
     * @return boolean
     */
    protected boolean isMacAddressAvailable(DeviceId deviceId, Port port, SubscriberAndDeviceInformation si) {
        AtomicBoolean isConfigured = new AtomicBoolean();
        isConfigured.set(true);

        si.uniTagList().stream().forEach(uniTagInfo -> {
            boolean configureMac = isMacAddressValid(uniTagInfo);
            boolean discoveredMac = false;
            Optional<Host> optHost = hostService.getConnectedHosts(new ConnectPoint(deviceId, port.number()))
                    .stream().filter(host -> host.vlan().equals(uniTagInfo.getPonCTag())).findFirst();
            if (optHost.isPresent() && optHost.get().mac() != null) {
                discoveredMac = true;
            }
            if (!configureMac && !discoveredMac) {
                isConfigured.set(false);
            }
        });

        return isConfigured.get();
    }

    protected MacAddress getMacAddress(DeviceId deviceId, Port port, UniTagInformation uniTagInfo) {
        boolean configureMac = isMacAddressValid(uniTagInfo);
        if (configureMac) {
            return MacAddress.valueOf(uniTagInfo.getConfiguredMacAddress());
        }

        Optional<Host> optHost = hostService.getConnectedHosts(new ConnectPoint(deviceId, port.number()))
                .stream().filter(host -> host.vlan().equals(uniTagInfo.getPonCTag())).findFirst();
        if (optHost.isPresent() && optHost.get().mac() != null) {
            return optHost.get().mac();
        }
        return null;
    }

    private boolean isMacAddressValid(UniTagInformation tagInformation) {
        return tagInformation.getConfiguredMacAddress() != null &&
                !tagInformation.getConfiguredMacAddress().trim().equals("") &&
                !MacAddress.NONE.equals(MacAddress.valueOf(tagInformation.getConfiguredMacAddress()));
    }

    protected void updateConnectPointStatus(ConnectPoint cp, OltFlowsStatus eapolStatus,
                                            OltFlowsStatus subscriberFlowsStatus, OltFlowsStatus dhcpStatus) {
        try {
            cpStatusWriteLock.lock();
            OltPortStatus status = cpStatus.get(cp);

            if (status == null) {
                status = new OltPortStatus(
                        eapolStatus != null ? eapolStatus : OltFlowsStatus.NONE,
                        subscriberFlowsStatus != null ? subscriberFlowsStatus : OltFlowsStatus.NONE,
                        dhcpStatus != null ? dhcpStatus : OltFlowsStatus.NONE
                );
            } else {
                if (eapolStatus != null) {
                    status.defaultEapolStatus = eapolStatus;
                }
                if (subscriberFlowsStatus != null) {
                    status.subscriberFlowsStatus = subscriberFlowsStatus;
                }
                if (dhcpStatus != null) {
                    status.dhcpStatus = dhcpStatus;
                }
            }

            cpStatus.put(cp, status);
        } finally {
            cpStatusWriteLock.unlock();
        }
    }

    public enum OltFlowsStatus {
        NONE,
        PENDING_ADD,
        ADDED,
        PENDING_REMOVE,
        REMOVED,
        ERROR
    }

    public static class OltPortStatus {
        // TODO consider adding a lastUpdated field, it may help with debugging
        public OltFlowsStatus defaultEapolStatus;
        public OltFlowsStatus subscriberFlowsStatus;
        // NOTE we need to keep track of the DHCP status as that is installed before the other flows
        // if macLearning is enabled (DHCP is needed to learn the MacAddress from the host)
        public OltFlowsStatus dhcpStatus;

        public OltPortStatus(OltFlowsStatus defaultEapolStatus,
                             OltFlowsStatus subscriberFlowsStatus, OltFlowsStatus dhcpStatus) {
            this.defaultEapolStatus = defaultEapolStatus;
            this.subscriberFlowsStatus = subscriberFlowsStatus;
            this.dhcpStatus = dhcpStatus;
        }
    }
}
