package org.opencord.olt.impl;

import org.onlab.packet.EthType;
import org.onlab.packet.VlanId;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.AnnotationKeys;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criteria;
import org.onosproject.net.flowobjective.DefaultFilteringObjective;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.ObjectiveContext;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.meter.MeterId;
import org.opencord.sadis.BandwidthProfileInformation;
import org.opencord.sadis.BaseInformationService;
import org.opencord.sadis.SadisService;
import org.opencord.sadis.SubscriberAndDeviceInformation;
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

    protected BaseInformationService<SubscriberAndDeviceInformation> subsService;
    protected BaseInformationService<BandwidthProfileInformation> bpService;

    private static final String APP_NAME = "org.opencord.olt";
    private ApplicationId appId;
    private static final Integer MAX_PRIORITY = 10000;
    private static final short EAPOL_DEFAULT_VLAN = 4091;
    private final Logger log = LoggerFactory.getLogger(getClass());

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

    @Activate
    public void activate() {
        bpService = sadisService.getBandwidthProfileService();
        subsService = sadisService.getSubscriberInfoService();
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication(APP_NAME);
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
        if (!oltMeterService.hasMeterByBandwidthProfile(sub.device.id(), bandwidthProfile)) {
            log.info("Missing meter for Bandwidth profile {} on device {}", bandwidthProfile, sub.device.id());

            if (!oltMeterService.hasPendingMeterByBandwidthProfile(sub.device.id(), bandwidthProfile)) {
                oltMeterService.createMeterForBp(sub.device.id(), bandwidthProfile);
            }
            throw new Exception(String.format("Meter is not yet available for %s on device %s",
                    bandwidthProfile, sub.device.id()));
        } else {
            // TODO handle flow installation error
            handleDefaultEapolFlow(sub, bandwidthProfile, FlowAction.ADD);
        }
    }

    private void removeDefaultFlows(DiscoveredSubscriber sub, String bandwidthProfile) throws Exception {
        // NOTE that we are not checking for meters as they must have been created to install the flow in first place
        handleDefaultEapolFlow(sub, bandwidthProfile, FlowAction.REMOVE);
    }

    @Override
    public void handleSubscriberFlows(DiscoveredSubscriber sub) {
        log.warn("handleSubscriberFlows unimplemented");
    }

    // NOTE this method can most likely be generalized to:
    // - install or remove flows
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

        if (meterId == null && action == FlowAction.ADD) {
            // NOTE this should NEVER happen
            // in the delete case the meter should still be there as we remove them when no flows are pointing to them
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
        if (action == FlowAction.ADD) {
            TrafficTreatment treatment = treatmentBuilder
                    .writeMetadata(createTechProfValueForWm(
                            VlanId.vlanId(EAPOL_DEFAULT_VLAN),
                            techProfileId, meterId), 0)
                    .setOutput(PortNumber.CONTROLLER)
                    .pushVlan()
                    .setVlanId(VlanId.vlanId(EAPOL_DEFAULT_VLAN))
                    .build();
            eapol = baseEapol
                    .withMeta(treatment);
        } else {
            eapol = baseEapol;
        }

        FilteringObjective eapolObjective = eapol
                .fromApp(appId)
                .withPriority(MAX_PRIORITY)
                .add(new ObjectiveContext() {
                    @Override
                    public void onSuccess(Objective objective) {
                        log.info("EAPOL flow {} for {}/{} with details {}",
                                action, sub.device.id(), sub.port.number(), objective);
                    }

                    @Override
                    public void onError(Objective objective, ObjectiveError error) {
                        log.error("Cannot {} eapol flow: {}", action, error);
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

    private Long createTechProfValueForWm(VlanId cVlan, int techProfileId, MeterId upstreamOltMeterId) {
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
}
