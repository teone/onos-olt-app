package org.opencord.olt.impl;

import com.google.common.collect.ImmutableMap;
import org.onlab.util.KryoNamespace;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.meter.Band;
import org.onosproject.net.meter.DefaultBand;
import org.onosproject.net.meter.DefaultMeterRequest;
import org.onosproject.net.meter.Meter;
import org.onosproject.net.meter.MeterContext;
import org.onosproject.net.meter.MeterEvent;
import org.onosproject.net.meter.MeterFailReason;
import org.onosproject.net.meter.MeterId;
import org.onosproject.net.meter.MeterKey;
import org.onosproject.net.meter.MeterListener;
import org.onosproject.net.meter.MeterRequest;
import org.onosproject.net.meter.MeterService;
import org.onosproject.net.meter.MeterState;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageService;
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

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

import static org.onlab.util.Tools.groupedThreads;
import static org.opencord.olt.impl.OsgiPropertyConstants.DELETE_METERS;
import static org.opencord.olt.impl.OsgiPropertyConstants.DELETE_METERS_DEFAULT;
import static org.slf4j.LoggerFactory.getLogger;

// TODO call the class OltMeterManager and the interface OltMeterService
@Component(immediate = true, property = {
        DELETE_METERS + ":Boolean=" + DELETE_METERS_DEFAULT,
})
public class OltMeterService implements OltMeterServiceInterface {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected volatile SadisService sadisService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected volatile MeterService meterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected OltDeviceServiceInterface oltDeviceService;

    private final Logger log = getLogger(getClass());
    protected BaseInformationService<BandwidthProfileInformation> bpService;
    private ApplicationId appId;
    private static final String APP_NAME = "org.opencord.olt";
    private final ReentrantReadWriteLock programmedMeterLock = new ReentrantReadWriteLock();
    private final Lock programmedMeterWriteLock = programmedMeterLock.writeLock();
    private final Lock programmedMeterReadLock = programmedMeterLock.readLock();

    /**
     * Programmed Meters status map.
     * Keeps track of which meter is programmed on which device for which BandwidthProfile.
     */
    protected Map<DeviceId, List<MeterData>> programmedMeters;

    protected BlockingQueue<OltMeterRequest> pendingMeters =
            new LinkedBlockingQueue<>();
    protected ScheduledExecutorService pendingMetersExecutor =
            Executors.newSingleThreadScheduledExecutor(groupedThreads("onos/olt",
                    "pending-meters-%d", log));

    private final MeterListener meterListener = new InternalMeterListener();
    protected ExecutorService pendingRemovalMetersExecutor =
            Executors.newFixedThreadPool(5, groupedThreads("onos/olt",
                    "pending-removal-meters-%d", log));

    /**
     * Map that contains a list of meters that needs to be removed.
     * We wait to get 3 METER_REFERENCE_COUNT_ZERO events before removing the meter
     * so that we're sure no flow is referencing it.
     */
    protected Map<DeviceId, Map<MeterKey, AtomicInteger>> pendingRemoveMeters;
    //TODO move to property
    protected int removeMeterEventNeeded = 3;

    /**
     * Delete meters when reference count drops to zero.
     */
    protected boolean deleteMeters = DELETE_METERS_DEFAULT;

    @Activate
    public void activate(ComponentContext context) {
        appId = coreService.registerApplication(APP_NAME);
        modified(context);
        KryoNamespace serializer = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .register(List.class)
                .register(MeterData.class)
                .register(MeterState.class)
                .register(MeterKey.class)
                .build();

        programmedMeters = storageService.<DeviceId, List<MeterData>>consistentMapBuilder()
                .withName("volt-programmed-meters")
                .withSerializer(Serializer.using(serializer))
                .withApplicationId(appId)
                .build().asJavaMap();

        pendingRemoveMeters = storageService.<DeviceId, Map<MeterKey, AtomicInteger>>consistentMapBuilder()
                .withName("volt-pending-remove-meters")
                .withSerializer(Serializer.using(serializer))
                .withApplicationId(appId)
                .build().asJavaMap();

        cfgService.registerProperties(getClass());

        bpService = sadisService.getBandwidthProfileService();

        pendingMetersExecutor.execute(this::processPendingMeters);

        meterService.addListener(meterListener);

        log.info("Started");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();

        Boolean d = Tools.isPropertyEnabled(properties, DELETE_METERS);
        if (d != null) {
            deleteMeters = d;
        }

        log.info("Modified. Values = deleteMeters: {}", deleteMeters);
    }

    @Deactivate
    public void deactivate(ComponentContext context) {
        cfgService.unregisterProperties(getClass(), false);
        pendingMetersExecutor.shutdown();
        meterService.removeListener(meterListener);
        log.info("Stopped");
    }

    @Override
    public Map<DeviceId, List<MeterData>> getProgrammedMeters() {
        try {
            programmedMeterReadLock.lock();
            return ImmutableMap.copyOf(programmedMeters);
        } finally {
            programmedMeterReadLock.unlock();
        }
    }

    @Override
    public boolean createMeter(DeviceId deviceId, String bandwidthProfile) {
        if (!hasMeterByBandwidthProfile(deviceId, bandwidthProfile)) {
            // NOTE this is at trace level as it's constantly called by the queue processor
            if (log.isTraceEnabled()) {
                log.trace("Missing meter for Bandwidth profile {} on device {}", bandwidthProfile, deviceId);
            }

            if (!hasPendingMeterByBandwidthProfile(deviceId, bandwidthProfile)) {
                createMeterForBp(deviceId, bandwidthProfile);
            }
            if (log.isTraceEnabled()) {
                log.trace("Meter is not yet available for {} on device {}",
                        bandwidthProfile, deviceId);
            }
            return false;
        }
        log.debug("Meter found for {} on device {}", bandwidthProfile, deviceId);
        return true;
    }

    @Override
    public boolean createMeters(DeviceId deviceId, SubscriberAndDeviceInformation si) {
        // Each UniTagInformation has up to 4 meters,
        // check and/or create all of them
        AtomicBoolean waitingOnMeter = new AtomicBoolean();
        waitingOnMeter.set(false);
        Map<String, List<String>> pendingMeters = new HashMap<>();
        si.uniTagList().stream().forEach(uniTagInfo -> {
            String serviceName = uniTagInfo.getServiceName();
            pendingMeters.put(serviceName, new LinkedList<>());
            String usBp = uniTagInfo.getUpstreamBandwidthProfile();
            String dsBp = uniTagInfo.getDownstreamBandwidthProfile();
            String oltUBp = uniTagInfo.getDownstreamOltBandwidthProfile();
            String oltDsBp = uniTagInfo.getUpstreamOltBandwidthProfile();
            if (!createMeter(deviceId, usBp)) {
                pendingMeters.get(serviceName).add(usBp);
                waitingOnMeter.set(true);
            }
            if (!createMeter(deviceId, dsBp)) {
                pendingMeters.get(serviceName).add(usBp);
                waitingOnMeter.set(true);
            }
            if (!createMeter(deviceId, oltUBp)) {
                pendingMeters.get(serviceName).add(usBp);
                waitingOnMeter.set(true);
            }
            if (!createMeter(deviceId, oltDsBp)) {
                pendingMeters.get(serviceName).add(usBp);
                waitingOnMeter.set(true);
            }
        });
        if (waitingOnMeter.get()) {
            if (log.isTraceEnabled()) {
                log.trace("Meters {} on device {} are not " +
                                "installed yet (requested by subscriber {})",
                        pendingMeters, deviceId, si.id());
            }
            return false;
        }
        return true;
    }

    /**
     * Returns true if a meter is present in the programmed meters map, only if status is ADDED.
     *
     * @param deviceId         the DeviceId on which to look for the meter
     * @param bandwidthProfile the Bandwidth profile associated with this meter
     * @return true if the meter is found
     */
    public boolean hasMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile) {
        try {
            programmedMeterReadLock.lock();
            List<MeterData> metersOnDevice = programmedMeters.get(deviceId);
            if (metersOnDevice == null || metersOnDevice.isEmpty()) {
                return false;
            }
            return metersOnDevice.stream().anyMatch(md -> md.bandwidthProfile.equals(bandwidthProfile)
                    && md.meterStatus.equals(MeterState.ADDED));
        } finally {
            programmedMeterReadLock.unlock();
        }
    }

    public boolean hasPendingMeterByBandwidthProfile(DeviceId deviceId, String bandwidthProfile) {
        try {
            programmedMeterReadLock.lock();
            List<MeterData> metersOnDevice = programmedMeters.get(deviceId);
            if (metersOnDevice == null || metersOnDevice.isEmpty()) {
                return false;
            }
            return metersOnDevice.stream().anyMatch(md -> md.bandwidthProfile.equals(bandwidthProfile)
                    && md.meterStatus.equals(MeterState.PENDING_ADD));
        } finally {
            programmedMeterReadLock.unlock();
        }
    }

    public MeterId getMeterIdForBandwidthProfile(DeviceId deviceId, String bandwidthProfile) {
        try {
            programmedMeterReadLock.lock();
            List<MeterData> metersOnDevice = programmedMeters.get(deviceId);
            if (metersOnDevice == null || metersOnDevice.isEmpty()) {
                return null;
            }
            MeterData meter = metersOnDevice.stream().
                    filter(md -> md.bandwidthProfile.equals(bandwidthProfile)
                            && md.meterStatus.equals(MeterState.ADDED))
                    .findFirst().orElse(null);
            if (meter != null) {
                log.debug("Found meter {} on device {} for bandwidth profile {}",
                        meter.meterId, deviceId, bandwidthProfile);
                return meter.meterId;
            }
            return null;
        } finally {
            programmedMeterReadLock.unlock();
        }
    }

    @Override
    public void purgeDeviceMeters(DeviceId deviceId) {
        log.debug("Purging meters on device {}", deviceId);
        meterService.purgeMeters(deviceId);

        // after we purge the meters we also need to clear the map
        try {
            programmedMeterWriteLock.lock();
            programmedMeters.remove(deviceId);
        } finally {
            programmedMeterWriteLock.unlock();
        }

        // and clear the event count
        // NOTE do we need a lock?
        pendingRemoveMeters.remove(deviceId);
    }

    /**
     * Schedules the creation of a meter for a given Bandwidth Profile on a given device.
     *
     * @param deviceId the DeviceId
     * @param bandwidthProfile     the BandwidthProfile ID
     */
    public void createMeterForBp(DeviceId deviceId, String bandwidthProfile) {
        BandwidthProfileInformation bpInfo = getBandwidthProfileInformation(bandwidthProfile);
        if (bpInfo == null) {
            log.error("BandwidthProfile {} information not found in sadis", bandwidthProfile);
            return;
        }

        log.info("Creating meter for {} on device {}", bpInfo, deviceId);

        List<Band> meterBands = createMeterBands(bpInfo);

        CompletableFuture<Object> meterFuture = new CompletableFuture<>();

        MeterRequest meterRequest = DefaultMeterRequest.builder()
                .withBands(meterBands)
                .withUnit(Meter.Unit.KB_PER_SEC)
                .withContext(new MeterContext() {
                    @Override
                    public void onSuccess(MeterRequest op) {
                        log.info("Meter for {} is installed on the device {}: {}",
                                 bandwidthProfile, deviceId, op);
                        meterFuture.complete(null);
                    }

                    @Override
                    public void onError(MeterRequest op, MeterFailReason reason) {
                        log.error("Failed installing meter on {} for {}",
                                deviceId, bandwidthProfile);
                        meterFuture.complete(reason);
                    }
                })
                .forDevice(deviceId)
                .fromApp(appId)
                .burst()
                .add();

        // create a request to encode it in the queue
        final AtomicReference<MeterId> meterIdRef = new AtomicReference<>();
        OltMeterRequest request = new OltMeterRequest(
                meterRequest,
                deviceId,
                bandwidthProfile,
                meterIdRef
        );

        if (!pendingMeters.contains(request)) {

            // adding meter in pending state to the programmedMeter map
            programmedMeterWriteLock.lock();
            List<MeterData> metersOnDevice = programmedMeters.get(request.deviceId);
            if (metersOnDevice == null) {
                metersOnDevice = new LinkedList<>();
            }

            MeterData meterData = new MeterData(
                    null, // the meter is not yet created
                    null,
                    MeterState.PENDING_ADD,
                    request.bandwidthProfile
            );
            metersOnDevice.add(meterData);
            programmedMeters.put(deviceId, metersOnDevice);
            programmedMeterWriteLock.unlock();

            // enqueue the request
            pendingMeters.add(request);
            log.debug("Added meter for {} to queue", bandwidthProfile);

            // once the request is enqueued wait for it to complete
            // so that we can store the meterId
            meterFuture.thenAccept(error -> {
                if (error != null) {
                    // NOTE if the meter installation fails add the meter back on the
                    // queue, thus we'll try to submit it again
                    return;
                }

                // then update the map with the MeterId
                try {
                    programmedMeterWriteLock.lock();
                    List<MeterData> existingMeters = programmedMeters.get(request.deviceId);

                    // update the meter to ADDED and add the CellId to it

                    // NOTE is there a cleaner way to get the index of the pending meter?
                    int idx = -1;
                    int curPos = 0;
                    for (MeterData md : existingMeters) {
                        if (md.meterStatus.equals(MeterState.PENDING_ADD) &&
                                md.bandwidthProfile.equals(request.bandwidthProfile)) {
                            idx = curPos;
                            break;
                        }
                        curPos++;
                    }

                    MeterData paMeter = existingMeters.get(idx);
                    paMeter.meterId = request.meterIdRef.get();
//                    paMeter.meterCellId = meter.meterCellId(); // NOTE do we need the meterCellId??
                    paMeter.meterStatus = MeterState.ADDED;

                    existingMeters.set(idx, paMeter);
                    programmedMeters.put(request.deviceId, existingMeters);
                } finally {
                    programmedMeterWriteLock.unlock();
                }
            });
        }
    }

    private List<Band> createMeterBands(BandwidthProfileInformation bpInfo) {
        List<Band> meterBands = new ArrayList<>();

        // add cir
        if (bpInfo.committedInformationRate() != 0) {
            meterBands.add(createMeterBand(bpInfo.committedInformationRate(), bpInfo.committedBurstSize()));
        }

        // check if both air and gir are set together in sadis
        // if they are, set air to 0
        if (bpInfo.assuredInformationRate() != 0 && bpInfo.guaranteedInformationRate() != 0) {
            bpInfo.setAssuredInformationRate(0);
        }

        // add pir
        long pir = bpInfo.peakInformationRate() != 0 ? bpInfo.peakInformationRate() : (bpInfo.exceededInformationRate()
                + bpInfo.committedInformationRate() + bpInfo.guaranteedInformationRate()
                + bpInfo.assuredInformationRate());

        Long pbs = bpInfo.peakBurstSize() != null ? bpInfo.peakBurstSize() :
                (bpInfo.exceededBurstSize() != null ? bpInfo.exceededBurstSize() : 0) +
                        (bpInfo.committedBurstSize() != null ? bpInfo.committedBurstSize() : 0);

        meterBands.add(createMeterBand(pir, pbs));

        // add gir
        if (bpInfo.guaranteedInformationRate() != 0) {
            meterBands.add(createMeterBand(bpInfo.guaranteedInformationRate(), 0L));
        }

        // add air
        // air is used in place of gir only if gir is
        // not present and air is not 0, see line 330.
        // Included for backwards compatibility, will be removed in VOLTHA 2.9.
        if (bpInfo.assuredInformationRate() != 0) {
            meterBands.add(createMeterBand(bpInfo.assuredInformationRate(), 0L));
        }

        return meterBands;
    }

    private Band createMeterBand(long rate, Long burst) {
        return DefaultBand.builder()
                .withRate(rate) //already Kbps
                .burstSize(burst) // already Kbits
                .ofType(Band.Type.DROP) // no matter
                .build();
    }

    private BandwidthProfileInformation getBandwidthProfileInformation(String bandwidthProfile) {
        if (!checkSadisRunning()) {
            return null;
        }
        if (bandwidthProfile == null) {
            return null;
        }
        return bpService.get(bandwidthProfile);
    }

    private boolean checkSadisRunning() {
        if (bpService == null) {
            log.warn("Sadis is not running");
            return false;
        }
        return true;
    }

    private void processPendingMeters() {
        log.info("Started processPendingMeters loop");
        while (true) {
            if (!pendingMeters.isEmpty()) {
                OltMeterRequest request = pendingMeters.peek();
                log.debug("Processing meter for bandwidth profile {} on device {}",
                        request.deviceId, request.bandwidthProfile);
                Meter meter = meterService.submit(request.meterRequest);

                // the installation request for the meter has been submitted
                // remove it from the queue
                pendingMeters.remove(request);

                // update the AtomicReference in the request so that we update the map
                // once the install future completes
                request.meterIdRef.set(meter.id());
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

    private class InternalMeterListener implements MeterListener {
        @Override
        public void event(MeterEvent meterEvent) {
            pendingRemovalMetersExecutor.execute(() -> {

                Meter meter = meterEvent.subject();
                if (!appId.equals(meter.appId())) {
                    return;
                }

                if (!oltDeviceService.isLocalLeader(meter.deviceId())) {
                    if (log.isTraceEnabled()) {
                        log.trace("ignoring meter event {} " +
                                          "as not leader for {}", meterEvent, meter.deviceId());
                    }
                    return;
                }

                log.debug("Received meter event {}", meterEvent);
                MeterKey key = MeterKey.key(meter.deviceId(), meter.id());
                if (meterEvent.type().equals(MeterEvent.Type.METER_REFERENCE_COUNT_ZERO)) {
                    log.info("Zero Count Reference event is received for meter {} on {}, " +
                                     "incrementing counter",
                             meter.id(), meter.deviceId());
                    incrementMeterCount(meter.deviceId(), key);
                    if (pendingRemoveMeters.get(meter.deviceId())
                            .get(key).get() == removeMeterEventNeeded) {
                        // only delete the meters if the app is configured to do so
                        if (deleteMeters) {
                            log.info("Meter {} on device {} is unused, removing it", meter.id(), meter.deviceId());
                            deleteMeter(meter.deviceId(), meter.id());
                        }
                    }
                }

                if (meterEvent.type().equals(MeterEvent.Type.METER_REMOVED)) {
                    removeMeterCount(meter, key);
                }
            });
        }

        private void removeMeterCount(Meter meter, MeterKey key) {
            pendingRemoveMeters.computeIfPresent(meter.deviceId(),
                    (id, meters) -> {
                        if (meters.get(key) == null) {
                            log.info("Meters is not pending " +
                                    "{} on {}", key, id);
                            return meters;
                        }
                        meters.remove(key);
                        return meters;
                    });
        }

        private void incrementMeterCount(DeviceId deviceId, MeterKey key) {
            if (key == null) {
                return;
            }
            pendingRemoveMeters.compute(deviceId,
                    (id, meters) -> {
                        if (meters == null) {
                            meters = new HashMap<>();

                        }
                        if (meters.get(key) == null) {
                            meters.put(key, new AtomicInteger(1));
                        }
                        meters.get(key).addAndGet(1);
                        return meters;
                    });
        }
    }

    private void deleteMeter(DeviceId deviceId, MeterId meterId) {
        Meter meter = meterService.getMeter(deviceId, meterId);
        if (meter != null) {
            MeterRequest meterRequest = DefaultMeterRequest.builder()
                    .withBands(meter.bands())
                    .withUnit(meter.unit())
                    .forDevice(deviceId)
                    .fromApp(appId)
                    .burst()
                    .remove();

            meterService.withdraw(meterRequest, meterId);
        }

        // remove the meter from local caching
        try {
            programmedMeterWriteLock.lock();
            List<MeterData> existingMeters = programmedMeters.get(deviceId);

            List<MeterData> newMeters = existingMeters.stream()
                    .filter(md -> md.meterId != null ? md.meterId.equals(meterId) : false)
                    .collect(Collectors.toList());
            programmedMeters.put(deviceId, newMeters);
        } finally {
            programmedMeterWriteLock.unlock();
        }
    }

}
