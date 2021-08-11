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
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flowobjective.DefaultFilteringObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
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
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.base.Strings.isNullOrEmpty;
import static org.onlab.util.Tools.get;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_TP_ID;
import static org.opencord.olt.impl.OsgiPropertyConstants.DEFAULT_TP_ID_DEFAULT;
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
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    protected BaseInformationService<SubscriberAndDeviceInformation> subsService;
    protected BaseInformationService<BandwidthProfileInformation> bpService;

    private static final String APP_NAME = "org.opencord.olt";
    private ApplicationId appId;
    private static final Integer MAX_PRIORITY = 10000;
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
    public void handleBasicPortFlows(DiscoveredSubscriber sub, String bandwidthProfile) throws Exception {

        // we only need to something if EAPOL is enabled
        if (!enableEapol) {
            return;
        }

        if (sub.status == DiscoveredSubscriber.Status.ADDED) {
            addDefaultFlows(sub, bandwidthProfile);
        } else if (sub.status == DiscoveredSubscriber.Status.REMOVED) {
            removeDefaultFlows(sub, bandwidthProfile);
        }

    }

    private void addDefaultFlows(DiscoveredSubscriber sub, String bandwidthProfile) throws Exception {
        oltMeterService.createMeter(sub.device.id(), bandwidthProfile);
        // TODO handle flow installation error
        handleDefaultEapolFlow(sub, bandwidthProfile, FlowAction.ADD);

        try {
            cpStatusWriteLock.lock();
            ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
            OltPortStatus status = new OltPortStatus(OltFlowsStatus.PENDING_ADD, OltFlowsStatus.NONE, null);
            cpStatus.put(cp, status);
        } finally {
            cpStatusWriteLock.unlock();
        }
    }

    private void removeDefaultFlows(DiscoveredSubscriber sub, String bandwidthProfile) throws Exception {
        // NOTE that we are not checking for meters as they must have been created to install the flow in first place
        handleDefaultEapolFlow(sub, bandwidthProfile, FlowAction.REMOVE);

        try {
            cpStatusWriteLock.lock();
            ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
            OltPortStatus status = new OltPortStatus(OltFlowsStatus.PENDING_REMOVE, OltFlowsStatus.NONE, null);
            cpStatus.put(cp, status);
        } finally {
            cpStatusWriteLock.unlock();
        }
    }

    @Override
    public void handleSubscriberFlows(DiscoveredSubscriber sub, String defaultBandwithProfile) throws Exception {
        // NOTE that we are taking defaultBandwithProfile as a parameter as that can be configured in the Olt component

        if (enableEapol) {
            if (hasDefaultEapol(sub.device.id(), sub.port.number())) {
                // remove EAPOL flow and throw exception so that we'll retry later
                removeDefaultFlows(sub, defaultBandwithProfile);
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

        // NOTE we may need to add the DHCP flow regardless so that the host can be discovered and the MacAddress added
        // this should be a TT only case
        if (isMacLearningEnabled(si) && !isMacAddressAvailable(sub.device.id(), sub.port, si)) {
            throw new Exception(String.format("Awaiting for macAddress on %s/%s (%s)",
                    sub.device.id(), sub.port.number(), sub.portName()));
        }

        // TODO add flows

        log.error("Provisioning of subscriber on {}/{} ({}) not supported yet",
                sub.device.id(), sub.port.number(), sub.portName());
    }

    public boolean hasDefaultEapol(DeviceId deviceId, PortNumber portNumber) {
        try {
            cpStatusReadLock.lock();
            ConnectPoint cp = new ConnectPoint(deviceId, portNumber);
            OltPortStatus status = cpStatus.get(cp);
            if (status == null) {
                return false;
            }
            return status.eapolStatus == OltFlowsStatus.ADDED || status.eapolStatus == OltFlowsStatus.PENDING_ADD;
        } finally {
            cpStatusReadLock.unlock();
        }
    }

    @Override
    public void purgeDeviceFlows(DeviceId deviceId) {
        log.debug("Purging flows on device {}", deviceId);
        flowRuleService.purgeFlowRules(deviceId);
    }

    // NOTE this method can most likely be generalized to:
    // - handle EAPOL flows with customer VLANs
    private void handleDefaultEapolFlow(DiscoveredSubscriber sub, String bandwidthProfile, FlowAction action)
            throws Exception {
        // NOTE we'll need to check in sadis once we install using the customer VLANs
        // SubscriberAndDeviceInformation si = subsService.get(sub.port.annotations().value(AnnotationKeys.PORT_NAME));
        // if (si == null) {
        //     throw new Exception(String.format("Subscriber %s information not found in sadis",
        //             sub.port.annotations().value(AnnotationKeys.PORT_NAME)));
        // }

        DefaultFilteringObjective.Builder filterBuilder = DefaultFilteringObjective.builder();
        TrafficTreatment.Builder treatmentBuilder = DefaultTrafficTreatment.builder();

        int techProfileId = getDefaultTechProfileId(sub.port);
        MeterId meterId = oltMeterService.getMeterIdForBandwidthProfile(sub.device.id(), bandwidthProfile);

        // in the delete case the meter should still be there as we remove
        // the meters only if no flows are pointing to them
        if (meterId == null) {
            throw new Exception(String.format("MeterId is null for BandwidthProfile %s on devices %s",
                    bandwidthProfile, sub.device.id()));
        }

        log.info("Preforming {} on default EAPOL flow for {}/{} and meterId {}",
                action, sub.device.id(), sub.port.number(), meterId);

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
                        VlanId.vlanId(EAPOL_DEFAULT_VLAN),
                        techProfileId, meterId), 0)
                .setOutput(PortNumber.CONTROLLER)
                .pushVlan()
                .setVlanId(VlanId.vlanId(EAPOL_DEFAULT_VLAN))
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
                        try {
                            cpStatusWriteLock.lock();
                            ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
                            OltPortStatus status = cpStatus.get(cp);
                            if (action.equals(FlowAction.ADD)) {
                                status.eapolStatus = OltFlowsStatus.ADDED;
                            } else {
                                status.eapolStatus = OltFlowsStatus.REMOVED;
                            }
                            cpStatus.put(cp, status);
                        } finally {
                            cpStatusWriteLock.unlock();
                        }
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.error("Cannot {} eapol flow: {}", action, error);
                        try {
                            cpStatusWriteLock.lock();
                            ConnectPoint cp = new ConnectPoint(sub.device.id(), sub.port.number());
                            OltPortStatus status = cpStatus.get(cp);
                            status.eapolStatus = OltFlowsStatus.ERROR;
                            cpStatus.put(cp, status);
                        } finally {
                            cpStatusWriteLock.unlock();
                        }
                    }
                });

        log.info("Created EAPOL filter to {} for {}/{}: {}", action, sub.device.id(), sub.port.number(), eapol);

        flowObjectiveService.filter(sub.device.id(), eapolObjective);
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

    private void processDhcpFilteringObjectives(DeviceId deviceId, Port port,
                                                FlowAction action, FlowDirection direction,
                                                int udpSrc, int udpDst, EthType ethType, byte protocol,
                                                MeterId meterId, MeterId oltMeterId, int techProfileId,
                                                VlanId cTag, VlanId unitagMatch, Byte vlanPcp) {

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
            }

            @Override
            public void onError(Objective objective, ObjectiveError error) {
                log.error("DHCP {} filter for {} failed {} because {}",
                        (ethType.equals(EthType.EtherType.IPV4.ethType())) ? V4 : V6, port,
                        action,
                        error);
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
     * @param deviceId DeviceId for this subscriber
     * @param port Port for this subscriber
     * @param si SubscriberAndDeviceInformation
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

    private boolean isMacAddressValid(UniTagInformation tagInformation) {
        return tagInformation.getConfiguredMacAddress() != null &&
                !tagInformation.getConfiguredMacAddress().trim().equals("") &&
                !MacAddress.NONE.equals(MacAddress.valueOf(tagInformation.getConfiguredMacAddress()));
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
        public OltFlowsStatus eapolStatus;
        public OltFlowsStatus subscriberFlowsStatus;
        public MacAddress macAddress;

        public OltPortStatus(OltFlowsStatus eapolStatus, OltFlowsStatus subscriberFlowsStatus, MacAddress macAddress) {
            this.eapolStatus = eapolStatus;
            this.subscriberFlowsStatus = subscriberFlowsStatus;
            this.macAddress = macAddress;
        }
    }
}
