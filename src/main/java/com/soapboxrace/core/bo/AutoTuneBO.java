package com.soapboxrace.core.bo;

import com.soapboxrace.core.dao.*;
import com.soapboxrace.core.jpa.*;
import com.soapboxrace.core.xmpp.OpenFireSoapBoxCli;
import com.soapboxrace.core.xmpp.XmppChat;

import javax.inject.Inject;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@ApplicationScoped
@Transactional
public class AutoTuneBO {

    @Inject
    private PersonaBO personaBO;

    @Inject
    private PersonaDAO personaDAO;

    @Inject
    private CarDAO carDAO;

    @Inject
    private CarClassesDAO carClassesDAO;

    @Inject
    private CarClassListDAO carClassListDAO;

    @Inject
    private ProductDAO productDAO;

    @Inject
    private PerformanceBO performanceBO;

    @Inject
    private DriverPersonaBO driverPersonaBO;

    @Inject
    private AutoTuneCacheDAO autoTuneCacheDAO;

    @Inject
    private ParameterBO parameterBO;

    /**
     * Stat priority for part selection.
     */
    public enum StatPriority {
        BALANCED,           // equal weight to all stats
        TOPSPEED,           // favor top speed parts
        ACCEL,              // favor acceleration parts
        HANDLING,           // favor handling parts
        TOPSPEED_HANDLING,  // topspeed on drivetrain, handling on chassis
        ACCEL_HANDLING      // accel on drivetrain, handling on chassis
    }

    // Stores pending tune proposals per persona ID
    private static final Map<Long, PendingTune> pendingTunes = new ConcurrentHashMap<>();

    // Pre-generated tune cache: prefix (hash_class_priority) -> TreeMap<level, setup>
    private static final Map<String, TreeMap<Integer, CachedTuneSetup>> tuneCache = new ConcurrentHashMap<>();

    // In-memory caches for static DB tables (loaded once at startup)
    private static Map<Integer, ProductEntity> productByHash;   // hash -> ProductEntity
    private static Map<String, CarClassListEntity> classListByName; // className -> entity
    private static List<CarClassListEntity> allClassList;
    private static String availableClassNamesStr;
    private static Map<Integer, CarClassesEntity> carClassesByHash; // physicsHash -> entity

    private static class CachedTuneSetup {
        final List<ProductEntity> parts;
        final int achievedRating;

        CachedTuneSetup(List<ProductEntity> parts, int achievedRating) {
            this.parts = parts;
            this.achievedRating = achievedRating;
        }
    }

    // Default safety limits for DFS iterations (overridden by PARAMETERS)
    private static final int DEFAULT_COMMAND_MAX_ITERATIONS = 1_000_000;
    private static final int DEFAULT_RELOAD_MAX_ITERATIONS = 10_000_000;

    // Precomputed per-priority data (immutable, shared across all cars/classes in batch)
    private static class PrecomputedParts {
        final int[][][] partStats;
        final boolean[] isPriorityCat;
        final int[][] maxRemaining;
        final int[] maxRemainingPrioCats;
        final int[][] minPartStats;
        final int n;
        final int prioOrd;
        final List<List<ProductEntity>> partLists;

        PrecomputedParts(int[][][] partStats, boolean[] isPriorityCat, int[][] maxRemaining,
                         int[] maxRemainingPrioCats, int[][] minPartStats, int n, int prioOrd,
                         List<List<ProductEntity>> partLists) {
            this.partStats = partStats; this.isPriorityCat = isPriorityCat;
            this.maxRemaining = maxRemaining; this.maxRemainingPrioCats = maxRemainingPrioCats;
            this.minPartStats = minPartStats; this.n = n; this.prioOrd = prioOrd;
            this.partLists = partLists;
        }
    }

    @PostConstruct
    public void init() {
        // Load static reference data into memory (these tables never change at runtime)
        try {
            allClassList = carClassListDAO.findAll();
            classListByName = new HashMap<>();
            StringBuilder sb = new StringBuilder();
            for (CarClassListEntity cls : allClassList) {
                classListByName.put(cls.getName(), cls);
                if (sb.length() > 0) sb.append(" ");
                sb.append(cls.getName());
            }
            availableClassNamesStr = sb.toString();

            List<CarClassesEntity> allCars = carClassesDAO.findAll();
            carClassesByHash = new HashMap<>();
            for (CarClassesEntity car : allCars) {
                if (car.getHash() != null) carClassesByHash.put(car.getHash(), car);
            }

            List<ProductEntity> allProducts = productDAO.findByLevelEnabled(
                    "NFSW_NA_EP_PERFORMANCEPARTS", "PERFORMANCEPART",
                    100, true, true, false);
            productByHash = new HashMap<>();
            for (ProductEntity p : allProducts) {
                productByHash.put(p.getHash(), p);
            }

            System.out.println("[AutoTune] Initialized: " + allClassList.size() + " classes, "
                    + carClassesByHash.size() + " car physics, " + productByHash.size() + " products cached");
        } catch (Exception e) {
            System.out.println("[AutoTune] Init warning (tables may not exist yet): " + e.getMessage());
            if (allClassList == null) allClassList = Collections.emptyList();
            if (classListByName == null) classListByName = Collections.emptyMap();
            if (carClassesByHash == null) carClassesByHash = Collections.emptyMap();
            if (productByHash == null) productByHash = Collections.emptyMap();
            if (availableClassNamesStr == null) availableClassNamesStr = "";
        }
    }

    /**
     * Reload static in-memory caches (car classes, class list, performance parts) from DB.
     * Call this before regenerating setups if the underlying data may have changed.
     */
    public void reloadStaticCaches() {
        try {
            allClassList = carClassListDAO.findAll();
            classListByName = new HashMap<>();
            StringBuilder sb = new StringBuilder();
            for (CarClassListEntity cls : allClassList) {
                classListByName.put(cls.getName(), cls);
                if (sb.length() > 0) sb.append(" ");
                sb.append(cls.getName());
            }
            availableClassNamesStr = sb.toString();

            List<CarClassesEntity> allCars = carClassesDAO.findAll();
            carClassesByHash = new HashMap<>();
            for (CarClassesEntity car : allCars) {
                if (car.getHash() != null) carClassesByHash.put(car.getHash(), car);
            }

            List<ProductEntity> allProducts = productDAO.findByLevelEnabled(
                    "NFSW_NA_EP_PERFORMANCEPARTS", "PERFORMANCEPART",
                    100, true, true, false);
            productByHash = new HashMap<>();
            for (ProductEntity p : allProducts) {
                productByHash.put(p.getHash(), p);
            }

            System.out.println("[AutoTune] Static caches reloaded: " + allClassList.size() + " classes, "
                    + carClassesByHash.size() + " car physics, " + productByHash.size() + " products");
        } catch (Exception e) {
            System.out.println("[AutoTune] Failed to reload static caches: " + e.getMessage());
        }
    }

    /**
     * Load cache keys from DB (lightweight — only used by preGenerate to check existing entries).
     */
    private Set<String> loadCacheKeysFromDB() {
        Set<String> keys = new HashSet<>();
        try {
            List<Object[]> rows = autoTuneCacheDAO.findAllKeys();
            for (Object[] row : rows) {
                String key = row[0] + "_" + row[1] + "_" + row[2] + "_" + row[3];
                keys.add(key);
            }
            System.out.println("[AutoTune] Loaded " + keys.size() + " cache keys from database");
        } catch (Exception e) {
            System.out.println("[AutoTune] No cached setups found in database (table may not exist yet)");
        }
        return keys;
    }

    // Thread pool for parallel job-level processing (work-stealing for better load balancing)
    private static final int THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final ForkJoinPool DFS_EXECUTOR = new ForkJoinPool(THREAD_COUNT);

    /**
     * Holds a pending auto-tune proposal waiting for player confirmation.
     */
    public static class PendingTune {
        public final List<ProductEntity> newParts;
        public final int estimatedRating;
        public final int netCashCost;      // net CASH cost (positive = pay, negative = gain)
        public final int netBoostCost;     // net BOOST cost (positive = pay, negative = gain)
        public final int purchaseCash;
        public final int purchaseBoost;
        public final int refundCash;
        public final int refundBoost;
        public final long carId;

        public PendingTune(List<ProductEntity> newParts, int estimatedRating,
                           int purchaseCash, int purchaseBoost,
                           int refundCash, int refundBoost, long carId) {
            this.newParts = newParts;
            this.estimatedRating = estimatedRating;
            this.purchaseCash = purchaseCash;
            this.purchaseBoost = purchaseBoost;
            this.refundCash = refundCash;
            this.refundBoost = refundBoost;
            this.netCashCost = purchaseCash - refundCash;
            this.netBoostCost = purchaseBoost - refundBoost;
            this.carId = carId;
        }
    }

    /**
     * Process the /tune command.
     */
    public void processTuneCommand(String[] args, PersonaEntity personaEntity, OpenFireSoapBoxCli openFireSoapBoxCli) {
        Long personaId = personaEntity.getPersonaId();

        if (args.length < 2) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_USAGE," + getAvailableClassNames());
            return;
        }

        String subCommand = args[1].trim().toLowerCase();

        switch (subCommand) {
            case "confirm":
                confirmTune(personaEntity, openFireSoapBoxCli);
                break;
            case "cancel":
                cancelTune(personaEntity, openFireSoapBoxCli);
                break;
            default:
                String targetClass = args[1].trim().toUpperCase();
                StatPriority priority = StatPriority.BALANCED;
                if (args.length >= 3) {
                    switch (args[2].trim().toLowerCase()) {
                        case "h": priority = StatPriority.HANDLING; break;
                        case "a": priority = StatPriority.ACCEL; break;
                        case "t": priority = StatPriority.TOPSPEED; break;
                        case "th": priority = StatPriority.TOPSPEED_HANDLING; break;
                        case "ah": priority = StatPriority.ACCEL_HANDLING; break;
                        default:
                            sendMessage(openFireSoapBoxCli, personaId,
                                    "SBRWR_TUNE_INVALID_PRIORITY," + args[2].trim());
                            return;
                    }
                }
                proposeTuneForClass(personaEntity, targetClass, priority, openFireSoapBoxCli);
                break;
        }
    }

    /**
     * Propose a tune configuration for the player's current car.
     */
    /**
     * Propose a tune for the player's car targeting a specific class.
     * Uses pre-generated cache when available, falls back to on-the-fly computation.
     */
    private void proposeTuneForClass(PersonaEntity personaEntity, String className, StatPriority priority, OpenFireSoapBoxCli openFireSoapBoxCli) {
        Long personaId = personaEntity.getPersonaId();
        try {
            proposeTuneForClassInternal(personaEntity, className, priority, openFireSoapBoxCli);
        } catch (Exception e) {
            System.out.println("[AutoTune] Error in /tune for persona " + personaId + ": " + e.getMessage());
            e.printStackTrace();
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_ERROR");
        }
    }

    private void proposeTuneForClassInternal(PersonaEntity personaEntity, String className, StatPriority priority, OpenFireSoapBoxCli openFireSoapBoxCli) {
        Long personaId = personaEntity.getPersonaId();

        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if (carEntity == null) {
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_NOCAR");
            return;
        }

        CarClassesEntity carClass = carClassesByHash.get(carEntity.getPhysicsProfileHash());
        if (carClass == null) {
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_NOPHYSICS");
            return;
        }

        if (carClass.isPerfLocked()) {
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_PERFLOCKED");
            return;
        }

        // Look up target class (in-memory)
        CarClassListEntity targetClassEntity = classListByName.get(className);
        if (targetClassEntity == null) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_UNKNOWNCLASS," + className + "," + availableClassNamesStr);
            return;
        }

        int classMaxRating = targetClassEntity.getMaxVal();
        int classMinRating = targetClassEntity.getMinVal();
        int stockRating = calculateRating(carClass, 0, 0, 0);

        if (stockRating > classMaxRating) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_STOCKEXCEEDS," + stockRating + "," + className + "," + classMaxRating);
            return;
        }

        // Determine effective level (max part level accessible)
        int effectiveLevel = personaEntity.getLevel();
        if (personaEntity.getPrestige() > 0) effectiveLevel = 100;

        // Try pre-generated cache first: find best match with level <= effectiveLevel
        String cachePrefix = carClass.getHash() + "_" + className + "_" + priority.name();
        CachedTuneSetup cached = null;
        // O(1) memory lookup via TreeMap.floorEntry
        try {
            TreeMap<Integer, CachedTuneSetup> levelMap = tuneCache.get(cachePrefix);
            if (levelMap != null) {
                Map.Entry<Integer, CachedTuneSetup> entry = levelMap.floorEntry(effectiveLevel);
                if (entry != null) cached = entry.getValue();
            }
        } catch (Exception e) {
            System.out.println("[AutoTune] Cache lookup error: " + e.getMessage());
        }

        // DB fallback if not in memory
        if (cached == null) {
            try {
                AutoTuneCacheEntity dbEntry = autoTuneCacheDAO.findBestForLevel(carClass.getHash(), className, priority.name(), effectiveLevel);
                if (dbEntry != null && dbEntry.getPartHashes() != null) {
                    List<ProductEntity> dbParts = new ArrayList<>();
                    for (String hashStr : dbEntry.getPartHashes().split(",")) {
                        try {
                            int h = Integer.parseInt(hashStr.trim());
                            ProductEntity p = productByHash.get(h);
                            if (p != null) dbParts.add(p);
                        } catch (Exception ignored) {}
                    }
                    if (!dbParts.isEmpty()) {
                        cached = new CachedTuneSetup(dbParts, dbEntry.getAchievedRating());
                        tuneCache.computeIfAbsent(cachePrefix, k -> new TreeMap<>()).put(dbEntry.getLevel(), cached);
                    }
                }
            } catch (Exception e) {
                System.out.println("[AutoTune] DB cache lookup error: " + e.getMessage());
            }
        }

        List<ProductEntity> bestCombination;
        int achievedRating;

        if (cached != null) {
            bestCombination = cached.parts;
            achievedRating = cached.achievedRating;
        } else {
            // Compute on-the-fly using in-memory product cache filtered by player level
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_CALCULATING");

            final int fLevel = effectiveLevel;
            List<ProductEntity> allPerfParts = productByHash.values().stream()
                    .filter(p -> p.getMinLevel() <= fLevel)
                    .collect(Collectors.toList());

            if (allPerfParts.isEmpty()) {
                sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_NOPARTS");
                return;
            }

            List<ProductEntity> usableParts = allPerfParts.stream()
                    .filter(p -> p.getTopSpeed() > 0 || p.getAccel() > 0 || p.getHandling() > 0)
                    .filter(p -> p.getSubType() == null || !p.getSubType().toLowerCase().contains("misc"))
                    .sorted(Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed())
                    .collect(Collectors.toList());

            if (usableParts.isEmpty()) {
                sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_NOPARTS");
                return;
            }

            Map<String, List<ProductEntity>> partsBySubType = usableParts.stream()
                    .collect(Collectors.groupingBy(p -> p.getSubType() != null ? p.getSubType() : "unknown"));

            int maxTS = 0, maxAC = 0, maxHA = 0;
            for (List<ProductEntity> subTypeParts : partsBySubType.values()) {
                ProductEntity best = subTypeParts.get(0);
                maxTS += best.getTopSpeed();
                maxAC += best.getAccel();
                maxHA += best.getHandling();
            }
            int maxRating = calculateRating(carClass, maxTS, maxAC, maxHA);

            if (maxRating < classMinRating) {
                sendMessage(openFireSoapBoxCli, personaId,
                        "SBRWR_TUNE_CANNOTREACH," + className + "," + maxRating + "," + classMinRating);
                return;
            }

            int effectiveTarget = Math.min(classMaxRating, maxRating);
            int commandMaxIter = parameterBO.getIntParam("SBRWR_AUTOTUNE_COMMAND_MAX_ITERATIONS", DEFAULT_COMMAND_MAX_ITERATIONS);
            bestCombination = findOptimalParts(carClass, partsBySubType, effectiveTarget, priority, commandMaxIter);

            if (bestCombination == null || bestCombination.isEmpty()) {
                sendMessage(openFireSoapBoxCli, personaId,
                        "SBRWR_TUNE_NOCOMBINATION," + className);
                return;
            }

            int ts = 0, ac = 0, ha = 0;
            int maxPartLevel = 0;
            for (ProductEntity part : bestCombination) {
                ts += part.getTopSpeed();
                ac += part.getAccel();
                ha += part.getHandling();
                if (part.getLevel() > maxPartLevel) maxPartLevel = part.getLevel();
            }
            achievedRating = calculateRating(carClass, ts, ac, ha);

            // Save to cache (memory + DB) keyed by max part level in the setup
            tuneCache.computeIfAbsent(cachePrefix, k -> new TreeMap<>()).put(maxPartLevel, new CachedTuneSetup(bestCombination, achievedRating));
            try {
                StringBuilder hashesStr = new StringBuilder();
                for (ProductEntity p : bestCombination) {
                    if (hashesStr.length() > 0) hashesStr.append(",");
                    hashesStr.append(p.getHash());
                }
                AutoTuneCacheEntity entity = new AutoTuneCacheEntity();
                entity.setPhysicsHash(carClass.getHash());
                entity.setClassName(className);
                entity.setPriority(priority.name());
                entity.setLevel(maxPartLevel);
                entity.setPartHashes(hashesStr.toString());
                entity.setAchievedRating(achievedRating);
                autoTuneCacheDAO.insertNewTx(entity);
            } catch (Exception e) {
                System.out.println("[AutoTune] Failed to persist cache for part level " + maxPartLevel + ": " + e.getMessage());
            }
        }

        // Validate achieved rating is within class bounds
        if (achievedRating < classMinRating) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_CANNOTREACH," + className + "," + achievedRating + "," + classMinRating);
            return;
        }

        // Calculate costs
        int purchaseCash = 0, purchaseBoost = 0;
        for (ProductEntity part : bestCombination) {
            if ("BOOST".equals(part.getCurrency())) {
                purchaseBoost += (int) part.getPrice();
            } else {
                purchaseCash += (int) part.getPrice();
            }
        }

        int refundCash = 0, refundBoost = 0;
        if (carEntity.getPerformanceParts() != null) {
            for (PerformancePartEntity currentPart : carEntity.getPerformanceParts()) {
                try {
                    ProductEntity product = productByHash.get(currentPart.getPerformancePartAttribHash());
                    if (product != null) {
                        if ("BOOST".equals(product.getCurrency())) {
                            refundBoost += (int) product.getResalePrice();
                        } else {
                            refundCash += (int) product.getResalePrice();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        int netCash = purchaseCash - refundCash;
        int netBoost = purchaseBoost - refundBoost;

        boolean cantAfford = false;
        if (netCash > 0 && personaEntity.getCash() < netCash) {
            cantAfford = true;
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_NEED_CASH," + String.format("%d", netCash) + "," + String.format("%d", (int) personaEntity.getCash()));
        }
        if (netBoost > 0 && personaEntity.getBoost() < netBoost) {
            cantAfford = true;
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_NEED_BOOST," + String.format("%d", netBoost) + "," + String.format("%d", (int) personaEntity.getBoost()));
        }
        if (cantAfford) {
            return;
        }

        PendingTune pendingTune = new PendingTune(bestCombination, achievedRating,
                purchaseCash, purchaseBoost, refundCash, refundBoost, carEntity.getId());
        pendingTunes.put(personaId, pendingTune);

        String currentRating = String.valueOf(carEntity.getRating());

        // Send proposal line: SBRWR_TUNE_PROPOSAL_<PRIORITY>,currentRating,achievedRating,className,partCount
        sendMessage(openFireSoapBoxCli, personaId,
                "SBRWR_TUNE_PROPOSAL_" + priority.name() + "," + currentRating + "," + achievedRating + "," + className + "," + bestCombination.size());

        // Build cost summary for confirm line
        StringBuilder costSummary = new StringBuilder();
        if (netCash != 0) {
            costSummary.append(netCash > 0 ? "-$" : "+$").append(String.format("%d", Math.abs(netCash)));
        }
        if (netBoost != 0) {
            if (costSummary.length() > 0) costSummary.append(" ");
            costSummary.append(netBoost > 0 ? "-" : "+").append(String.format("%d", Math.abs(netBoost))).append(" boost");
        }
        if (costSummary.length() == 0) costSummary.append("$0");

        int taxCash = (int) (purchaseCash * 0.05);
        sendMessage(openFireSoapBoxCli, personaId,
                "SBRWR_TUNE_CONFIRM," + costSummary + "," + (taxCash > 0 ? "$" + String.format("%d", taxCash) : "$0"));
    }

    /**
     * Confirm and apply a pending tune.
     */
    private void confirmTune(PersonaEntity personaEntity, OpenFireSoapBoxCli openFireSoapBoxCli) {
        Long personaId = personaEntity.getPersonaId();
        PendingTune pending = pendingTunes.remove(personaId);

        if (pending == null) {
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_NOPENDING");
            return;
        }

        // Re-fetch persona for latest cash
        personaEntity = personaDAO.find(personaId);

        // Get the current car and verify it's the same one
        CarEntity carEntity = personaBO.getDefaultCarEntity(personaId);
        if (carEntity == null || !carEntity.getId().equals(pending.carId)) {
            sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_CARCHANGED");
            return;
        }

        // Double-check funds
        if (pending.netCashCost > 0 && personaEntity.getCash() < pending.netCashCost) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_NEED_CASH," + String.format("%d", pending.netCashCost) + "," + String.format("%d", (int) personaEntity.getCash()));
            return;
        }
        if (pending.netBoostCost > 0 && personaEntity.getBoost() < pending.netBoostCost) {
            sendMessage(openFireSoapBoxCli, personaId,
                    "SBRWR_TUNE_NEED_BOOST," + String.format("%d", pending.netBoostCost) + "," + String.format("%d", (int) personaEntity.getBoost()));
            return;
        }

        // Clear current performance parts and set new ones
        Set<PerformancePartEntity> newPartEntities = new HashSet<>();
        for (ProductEntity product : pending.newParts) {
            PerformancePartEntity partEntity = new PerformancePartEntity();
            partEntity.setCar(carEntity);
            partEntity.setPerformancePartAttribHash(product.getHash());
            newPartEntities.add(partEntity);
        }

        if (carEntity.getPerformanceParts() == null) {
            carEntity.setPerformanceParts(newPartEntities);
        } else {
            carEntity.getPerformanceParts().clear();
            carEntity.getPerformanceParts().addAll(newPartEntities);
        }

        // Recalculate rating
        performanceBO.calcNewCarClass(carEntity);

        // Save car
        carDAO.update(carEntity);

        // Update currencies
        double newCash = personaEntity.getCash() - pending.netCashCost;
        driverPersonaBO.updateCash(personaEntity, newCash);
        if (pending.netBoostCost != 0) {
            personaEntity.setBoost(personaEntity.getBoost() - pending.netBoostCost);
            personaDAO.update(personaEntity);
        }

        // Find class name for the new rating
        String className = "?";
        try {
            className = findClassNameByRating(carEntity.getRating());
        } catch (Exception ignored) {
        }

        // Build cost summary
        StringBuilder costSummary = new StringBuilder();
        if (pending.netCashCost != 0) {
            costSummary.append(pending.netCashCost > 0 ? "-$" : "+$").append(String.format("%d", Math.abs(pending.netCashCost)));
        }
        if (pending.netBoostCost != 0) {
            if (costSummary.length() > 0) costSummary.append(" ");
            costSummary.append(pending.netBoostCost > 0 ? "-" : "+").append(String.format("%d", Math.abs(pending.netBoostCost))).append(" boost");
        }
        if (costSummary.length() == 0) costSummary.append("$0");

        int taxCash = (int) (pending.purchaseCash * 0.05);

        sendMessage(openFireSoapBoxCli, personaId,
                "SBRWR_TUNE_APPLIED," + carEntity.getRating() + "," + className + "," + pending.newParts.size() + "," + costSummary + "," + (taxCash > 0 ? "$" + String.format("%d", taxCash) : "$0"));
        sendMessage(openFireSoapBoxCli, personaId, "SBRWR_TUNE_SAFEHOUSE");
    }

    /**
     * Cancel a pending tune.
     */
    private void cancelTune(PersonaEntity personaEntity, OpenFireSoapBoxCli openFireSoapBoxCli) {
        PendingTune removed = pendingTunes.remove(personaEntity.getPersonaId());
        if (removed != null) {
            sendMessage(openFireSoapBoxCli, personaEntity.getPersonaId(), "SBRWR_TUNE_CANCELLED");
        } else {
            sendMessage(openFireSoapBoxCli, personaEntity.getPersonaId(), "SBRWR_TUNE_NOPENDING_CANCEL");
        }
    }

    // --- Category classification for stat-priority part selection ---

    private static boolean isDrivetrainCategory(String subType) {
        if (subType == null) return false;
        String s = subType.toLowerCase();
        return s.contains("engine") || s.contains("turbo") || s.contains("forced")
                || s.contains("induction") || s.contains("transmission");
    }

    private static boolean isChassisCategory(String subType) {
        if (subType == null) return false;
        String s = subType.toLowerCase();
        return s.contains("brake") || s.contains("tire") || s.contains("tyre") || s.contains("suspension");
    }

    private static boolean isPriorityCategory(String subType, StatPriority priority) {
        switch (priority) {
            case TOPSPEED:
            case ACCEL:
                return isDrivetrainCategory(subType);
            case HANDLING:
                return isChassisCategory(subType);
            case TOPSPEED_HANDLING:
            case ACCEL_HANDLING:
                return isDrivetrainCategory(subType) || isChassisCategory(subType);
            default:
                return false;
        }
    }

    /**
     * Deduplicate parts by (topSpeed, accel, handling) stats.
     * Keeps the cheapest part per unique stat combination.
     */
    private List<ProductEntity> deduplicateByStats(List<ProductEntity> parts) {
        Map<String, ProductEntity> bestByStats = new LinkedHashMap<>();
        for (ProductEntity part : parts) {
            String key = part.getTopSpeed() + "|" + part.getAccel() + "|" + part.getHandling();
            ProductEntity existing = bestByStats.get(key);
            if (existing == null || part.getPrice() < existing.getPrice()) {
                bestByStats.put(key, part);
            }
        }
        return new ArrayList<>(bestByStats.values());
    }

    /**
     * Precompute sorted/deduped parts data for a given priority.
     * Called once per priority in batch mode, shared across all cars/classes.
     */
    private static PrecomputedParts precomputeForPriority(Map<String, List<ProductEntity>> partsBySubType, StatPriority priority) {
        List<String> subTypes = new ArrayList<>(partsBySubType.keySet());
        Map<String, List<ProductEntity>> deduped = new LinkedHashMap<>();
        for (String subType : subTypes) {
            List<ProductEntity> parts = new ArrayList<>(partsBySubType.get(subType));
            parts.sort(partComparator(priority, isPriorityCategory(subType, priority), subType));
            Map<String, ProductEntity> best = new LinkedHashMap<>();
            for (ProductEntity p : parts) {
                String key = p.getTopSpeed() + "|" + p.getAccel() + "|" + p.getHandling();
                ProductEntity ex = best.get(key);
                if (ex == null || p.getPrice() < ex.getPrice()) best.put(key, p);
            }
            deduped.put(subType, new ArrayList<>(best.values()));
        }
        subTypes.sort((a, b) -> {
            if (priority != StatPriority.BALANCED) {
                boolean aPrio = isPriorityCategory(a, priority);
                boolean bPrio = isPriorityCategory(b, priority);
                if (aPrio && !bPrio) return -1;
                if (!aPrio && bPrio) return 1;
            }
            return Integer.compare(deduped.get(a).size(), deduped.get(b).size());
        });
        int n = subTypes.size();
        List<List<ProductEntity>> partLists = new ArrayList<>(n);
        String[] catNames = new String[n];
        for (int i = 0; i < n; i++) { partLists.add(deduped.get(subTypes.get(i))); catNames[i] = subTypes.get(i); }
        boolean[] isPrioCat = new boolean[n];
        for (int i = 0; i < n; i++) {
            switch (priority) {
                case TOPSPEED: case ACCEL: isPrioCat[i] = isDrivetrainCategory(catNames[i]); break;
                case HANDLING: isPrioCat[i] = isChassisCategory(catNames[i]); break;
                case TOPSPEED_HANDLING: case ACCEL_HANDLING: isPrioCat[i] = isDrivetrainCategory(catNames[i]) || isChassisCategory(catNames[i]); break;
                default: isPrioCat[i] = false;
            }
        }
        int[][][] partStats = new int[n][][];
        for (int i = 0; i < n; i++) {
            List<ProductEntity> pl = partLists.get(i);
            partStats[i] = new int[pl.size()][3];
            for (int j = 0; j < pl.size(); j++) {
                ProductEntity p = pl.get(j);
                partStats[i][j][0] = p.getTopSpeed(); partStats[i][j][1] = p.getAccel(); partStats[i][j][2] = p.getHandling();
            }
        }
        int[][] maxRemaining = new int[n + 1][4];
        for (int d = n - 1; d >= 0; d--) {
            System.arraycopy(maxRemaining[d + 1], 0, maxRemaining[d], 0, 4);
            if (partStats[d].length > 0) {
                int bTS = 0, bAC = 0, bHA = 0;
                for (int[] s : partStats[d]) { if (s[0] > bTS) bTS = s[0]; if (s[1] > bAC) bAC = s[1]; if (s[2] > bHA) bHA = s[2]; }
                maxRemaining[d][0] += bTS; maxRemaining[d][1] += bAC; maxRemaining[d][2] += bHA; maxRemaining[d][3]++;
            }
        }
        int[] maxRemPrioCats = new int[n + 1];
        for (int d = n - 1; d >= 0; d--) {
            maxRemPrioCats[d] = maxRemPrioCats[d + 1];
            if (partStats[d].length > 0 && isPrioCat[d]) maxRemPrioCats[d]++;
        }
        int[][] minPartStats = new int[n][3];
        for (int i = 0; i < n; i++) {
            if (partStats[i].length > 0) {
                int minT = Integer.MAX_VALUE;
                for (int[] s : partStats[i]) {
                    int t = s[0] + s[1] + s[2];
                    if (t < minT) { minT = t; minPartStats[i][0] = s[0]; minPartStats[i][1] = s[1]; minPartStats[i][2] = s[2]; }
                }
            }
        }
        return new PrecomputedParts(partStats, isPrioCat, maxRemaining, maxRemPrioCats, minPartStats, n, priorityOrdinal(priority), partLists);
    }

    /**
     * Returns a comparator that sorts parts descending by the prioritized stat.
     * Priority categories get a heavier weight on the prioritized stat.
     */
    private static Comparator<ProductEntity> partComparator(StatPriority priority, boolean isPriorityCat, String subType) {
        int w = isPriorityCat ? 10 : 3;
        switch (priority) {
            case TOPSPEED:
                return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() * w + p.getAccel() + p.getHandling()).reversed();
            case ACCEL:
                return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() * w + p.getHandling()).reversed();
            case HANDLING:
                return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling() * w).reversed();
            case TOPSPEED_HANDLING:
                if (isDrivetrainCategory(subType)) {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() * w + p.getAccel() + p.getHandling()).reversed();
                } else if (isChassisCategory(subType)) {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling() * w).reversed();
                } else {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed();
                }
            case ACCEL_HANDLING:
                if (isDrivetrainCategory(subType)) {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() * w + p.getHandling()).reversed();
                } else if (isChassisCategory(subType)) {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling() * w).reversed();
                } else {
                    return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed();
                }
            default: // BALANCED
                return Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed();
        }
    }

    private List<ProductEntity> findOptimalParts(CarClassesEntity carClass,
                                                  Map<String, List<ProductEntity>> partsBySubType,
                                                  int targetRating, StatPriority priority, int maxIterations) {
        // Use the same optimized path as batch: precompute + greedy + hillclimb + 2-opt + serial DFS
        PrecomputedParts pp = precomputeForPriority(partsBySubType, priority);
        double[] physics = cacheCarPhysics(carClass);

        int[] choices = findOptimalPartsBatch(physics, pp, targetRating, maxIterations);
        if (choices == null) return null;

        List<ProductEntity> result = new ArrayList<>();
        for (int i = 0; i < pp.n; i++) {
            if (choices[i] >= 0) {
                result.add(pp.partLists.get(i).get(choices[i]));
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Pre-cached car physics as delta coefficients for faster rating computation.
     * Uses algebraic simplification: fts = base_ts + tt*dts_tt + ta*dts_ta + th*dts_th
     * [0]=tsBase [1]=dts_th [2]=dts_ta [3]=dts_tt
     * [4]=acBase [5]=dac_th [6]=dac_ta [7]=dac_tt
     * [8]=haBase [9]=dha_th [10]=dha_ta [11]=dha_tt
     */
    private static double[] cacheCarPhysics(CarClassesEntity c) {
        double tsBase = c.getTsStock().doubleValue();
        double acBase = c.getAcStock().doubleValue();
        double haBase = c.getHaStock().doubleValue();
        return new double[] {
            tsBase, c.getTsVar1().doubleValue() - tsBase, c.getTsVar2().doubleValue() - tsBase, c.getTsVar3().doubleValue() - tsBase,
            acBase, c.getAcVar1().doubleValue() - acBase, c.getAcVar2().doubleValue() - acBase, c.getAcVar3().doubleValue() - acBase,
            haBase, c.getHaVar1().doubleValue() - haBase, c.getHaVar2().doubleValue() - haBase, c.getHaVar3().doubleValue() - haBase
        };
    }

    /**
     * Fast rating calculation using pre-computed delta coefficients.
     * fStat = base + tt*d_tt + ta*d_ta + th*d_th (avoids computing fc separately)
     */
    private static int calculateRatingFast(double[] p, int topSpeed, int accel, int handling) {
        double tt = topSpeed * 0.0099999998;
        double ta = accel * 0.0099999998;
        double th = handling * 0.0099999998;
        double tc = 1.0 / (((tt + ta + th) * 0.66666669) + 1.0);
        tt *= tc; ta *= tc; th *= tc;
        double fts = p[0] + tt * p[3] + ta * p[2] + th * p[1];
        double fac = p[4] + tt * p[7] + ta * p[6] + th * p[5];
        double fha = p[8] + tt * p[11] + ta * p[10] + th * p[9];
        return (int) (((int) fts + (int) fac + (int) fha) / 3.0);
    }

    /**
     * Score a part combination. Higher is better.
     * priorityOrd: 0=TOPSPEED, 1=ACCEL, 2=HANDLING, 3=BALANCED, 4=TOPSPEED_HANDLING, 5=ACCEL_HANDLING
     */
    private static long computeScoreOrd(int priorityOrd, int rating, int topSpeed, int accel, int handling,
                              int usedCategories, int usedPrioCats) {
        switch (priorityOrd) {
            case 0: return (long) rating * 10000000L + (long) usedPrioCats * 1000000L + (long) topSpeed;
            case 1: return (long) rating * 10000000L + (long) usedPrioCats * 1000000L + (long) accel;
            case 2: return (long) rating * 10000000L + (long) usedPrioCats * 1000000L + (long) handling;
            case 4: return (long) rating * 10000000L + (long) usedPrioCats * 1000000L + (long) (topSpeed + handling);
            case 5: return (long) rating * 10000000L + (long) usedPrioCats * 1000000L + (long) (accel + handling);
            default: return (long) usedCategories * 100000000L + (long) rating * 100L;
        }
    }

    private static int priorityOrdinal(StatPriority p) {
        switch (p) {
            case TOPSPEED: return 0;
            case ACCEL: return 1;
            case HANDLING: return 2;
            case TOPSPEED_HANDLING: return 4;
            case ACCEL_HANDLING: return 5;
            default: return 3;
        }
    }

    // ========== OPTIMIZED PATH (serial DFS, no atomics, no ForkJoinPool per-DFS) ==========

    /**
     * Find optimal parts for batch pre-generation. Uses precomputed data + serial DFS.
     * No ForkJoinPool overhead per call - parallelism is at the job level.
     */
    private static int[] findOptimalPartsBatch(double[] physics, PrecomputedParts pp, int targetRating, int maxIterations) {
        int n = pp.n;
        int[][][] partStats = pp.partStats;
        boolean[] isPrioCat = pp.isPriorityCat;
        int prioOrd = pp.prioOrd;

        // bestOfEach shortcut: if all strongest parts fit, return instantly
        boolean allHave = true;
        int bts = 0, bac = 0, bha = 0;
        for (int i = 0; i < n; i++) {
            if (partStats[i].length > 0) {
                bts += partStats[i][0][0]; bac += partStats[i][0][1]; bha += partStats[i][0][2];
            } else { allHave = false; }
        }
        if (allHave && calculateRatingFast(physics, bts, bac, bha) <= targetRating) {
            int[] r = new int[n]; Arrays.fill(r, 0); return r;
        }

        // Multi-greedy seeds + hill-climbing from each + 2-opt on best
        long bestScore = -1;
        int[] bestChoices = new int[n]; Arrays.fill(bestChoices, -1);

        {
            int[][] seedSets = new int[4][n];
            for (int si = 0; si < 4; si++) Arrays.fill(seedSets[si], -1);
            // Seed 0: top-down
            { int gts=0,gac=0,gha=0;
              for(int i=0;i<n;i++) for(int j=0;j<partStats[i].length;j++){
                  int nt=gts+partStats[i][j][0],na=gac+partStats[i][j][1],nh=gha+partStats[i][j][2];
                  if(calculateRatingFast(physics,nt,na,nh)<=targetRating){seedSets[0][i]=j;gts=nt;gac=na;gha=nh;break;}}
            }
            // Seed 1: bottom-up
            { int gts=0,gac=0,gha=0;
              for(int i=0;i<n;i++) for(int j=partStats[i].length-1;j>=0;j--){
                  int nt=gts+partStats[i][j][0],na=gac+partStats[i][j][1],nh=gha+partStats[i][j][2];
                  if(calculateRatingFast(physics,nt,na,nh)<=targetRating){seedSets[1][i]=j;gts=nt;gac=na;gha=nh;break;}}
            }
            // Seed 2: reverse order
            { int gts=0,gac=0,gha=0;
              for(int i=n-1;i>=0;i--) for(int j=0;j<partStats[i].length;j++){
                  int nt=gts+partStats[i][j][0],na=gac+partStats[i][j][1],nh=gha+partStats[i][j][2];
                  if(calculateRatingFast(physics,nt,na,nh)<=targetRating){seedSets[2][i]=j;gts=nt;gac=na;gha=nh;break;}}
            }
            // Seed 3: mid-point
            { int gts=0,gac=0,gha=0;
              for(int i=0;i<n;i++){int mid=partStats[i].length/2;
                  for(int j=mid;j<partStats[i].length;j++){int nt=gts+partStats[i][j][0],na=gac+partStats[i][j][1],nh=gha+partStats[i][j][2];
                      if(calculateRatingFast(physics,nt,na,nh)<=targetRating){seedSets[3][i]=j;gts=nt;gac=na;gha=nh;break;}}
                  if(seedSets[3][i]<0) for(int j=mid-1;j>=0;j--){int nt=gts+partStats[i][j][0],na=gac+partStats[i][j][1],nh=gha+partStats[i][j][2];
                      if(calculateRatingFast(physics,nt,na,nh)<=targetRating){seedSets[3][i]=j;gts=nt;gac=na;gha=nh;break;}}}
            }

            // Hill-climb each seed independently
            for (int si = 0; si < 4; si++) {
                int[] sc = seedSets[si];
                int hts=0,hac=0,hha=0;
                for(int i=0;i<n;i++) if(sc[i]>=0){hts+=partStats[i][sc[i]][0];hac+=partStats[i][sc[i]][1];hha+=partStats[i][sc[i]][2];}
                int hc=0,hp=0; for(int i=0;i<n;i++){if(sc[i]>=0){hc++;if(isPrioCat[i])hp++;}}
                long cur = hc > 0 ? computeScoreOrd(prioOrd,calculateRatingFast(physics,hts,hac,hha),hts,hac,hha,hc,hp) : -1;
                boolean improved = true;
                while (improved) { improved = false;
                    for (int i=0;i<n;i++) {
                        int oj=sc[i]; int oT=oj>=0?partStats[i][oj][0]:0,oA=oj>=0?partStats[i][oj][1]:0,oH=oj>=0?partStats[i][oj][2]:0;
                        for (int j=0;j<partStats[i].length;j++) { if(j==oj)continue;
                            int nt=hts-oT+partStats[i][j][0],na=hac-oA+partStats[i][j][1],nh=hha-oH+partStats[i][j][2];
                            if(calculateRatingFast(physics,nt,na,nh)<=targetRating){
                                int tc=hc,tp=hp; if(oj<0){tc++;if(isPrioCat[i])tp++;}
                                long ns=computeScoreOrd(prioOrd,calculateRatingFast(physics,nt,na,nh),nt,na,nh,tc,tp);
                                if(ns>cur){cur=ns;sc[i]=j;hts=nt;hac=na;hha=nh;hc=tc;hp=tp;improved=true;break;}
                            }
                        }
                        if (oj >= 0) {
                            int nt=hts-oT,na=hac-oA,nh=hha-oH; int tc=hc-1,tp=hp-(isPrioCat[i]?1:0);
                            long ns=tc>0?computeScoreOrd(prioOrd,calculateRatingFast(physics,nt,na,nh),nt,na,nh,tc,tp):-1;
                            if(ns>cur){cur=ns;sc[i]=-1;hts=nt;hac=na;hha=nh;hc=tc;hp=tp;improved=true;}
                        }
                    }
                }
                if (cur > bestScore) { bestScore = cur; System.arraycopy(sc, 0, bestChoices, 0, n); }
            }
        }

        // 2-opt on best result
        {
            int hts=0,hac=0,hha=0;
            for(int i=0;i<n;i++) if(bestChoices[i]>=0){hts+=partStats[i][bestChoices[i]][0];hac+=partStats[i][bestChoices[i]][1];hha+=partStats[i][bestChoices[i]][2];}
            boolean improved = true;
            while (improved) { improved = false;
                for (int a=0;a<n&&!improved;a++) for (int b=a+1;b<n&&!improved;b++) {
                    int oAj=bestChoices[a],oBj=bestChoices[b];
                    int oAts=oAj>=0?partStats[a][oAj][0]:0,oAac=oAj>=0?partStats[a][oAj][1]:0,oAha=oAj>=0?partStats[a][oAj][2]:0;
                    int oBts=oBj>=0?partStats[b][oBj][0]:0,oBac=oBj>=0?partStats[b][oBj][1]:0,oBha=oBj>=0?partStats[b][oBj][2]:0;
                    int bTS=hts-oAts-oBts,bAC=hac-oAac-oBac,bHA=hha-oAha-oBha;
                    for (int ja=-1;ja<partStats[a].length&&!improved;ja++) {
                        int aTS=ja>=0?partStats[a][ja][0]:0,aAC=ja>=0?partStats[a][ja][1]:0,aHA=ja>=0?partStats[a][ja][2]:0;
                        for (int jb=-1;jb<partStats[b].length;jb++) {
                            if(ja==oAj&&jb==oBj)continue;
                            int bT2=jb>=0?partStats[b][jb][0]:0,bA2=jb>=0?partStats[b][jb][1]:0,bH2=jb>=0?partStats[b][jb][2]:0;
                            int nt=bTS+aTS+bT2,na=bAC+aAC+bA2,nh=bHA+aHA+bH2;
                            if(calculateRatingFast(physics,nt,na,nh)<=targetRating){
                                int tc=0,tp=0;for(int k=0;k<n;k++){int sel=(k==a)?ja:(k==b)?jb:bestChoices[k];if(sel>=0){tc++;if(isPrioCat[k])tp++;}}
                                long ns=tc>0?computeScoreOrd(prioOrd,calculateRatingFast(physics,nt,na,nh),nt,na,nh,tc,tp):-1;
                                if(ns>bestScore){bestScore=ns;bestChoices[a]=ja;bestChoices[b]=jb;hts=nt;hac=na;hha=nh;improved=true;break;}
                            }
                        }
                    }
                }
            }
        }

        // Serial DFS with low iteration limit
        int[] choices = new int[n]; Arrays.fill(choices, -1);
        int[] dfsBest = new int[n]; System.arraycopy(bestChoices, 0, dfsBest, 0, n);
        long[] dfsBestScore = {bestScore};
        int[] iterCount = {0};
        dfsBatch(physics, partStats, isPrioCat, n, pp.maxRemaining, pp.maxRemainingPrioCats, pp.minPartStats,
                 choices, dfsBest, dfsBestScore, targetRating, prioOrd, 0, 0, 0, 0, 0, 0, iterCount, maxIterations);

        boolean hasResult = false;
        for (int i = 0; i < n; i++) { if (dfsBest[i] >= 0) { hasResult = true; break; } }
        return hasResult ? dfsBest : null;
    }

    /**
     * Ultra-lean serial DFS for batch mode. No atomics, no throttle, no ForkJoinPool.
     * All pruning from the parallel version is preserved.
     */
    private static void dfsBatch(double[] physics, int[][][] partStats, boolean[] isPrioCat, int n,
                                  int[][] maxRemaining, int[] maxRemPrioCats, int[][] minPartStats,
                                  int[] choices, int[] bestChoices, long[] bestScore,
                                  int targetRating, int prioOrd, int depth,
                                  int ts, int ac, int ha, int cats, int prioCats, int[] iterCount, int maxIterations) {
        if (++iterCount[0] > maxIterations) return;

        if (depth >= n) {
            int rating = calculateRatingFast(physics, ts, ac, ha);
            if (rating <= targetRating) {
                long score = computeScoreOrd(prioOrd, rating, ts, ac, ha, cats, prioCats);
                if (score > bestScore[0]) {
                    bestScore[0] = score;
                    System.arraycopy(choices, 0, bestChoices, 0, n);
                }
            }
            return;
        }

        long eff = bestScore[0];

        // Upper-bound pruning
        if (eff >= 0) {
            int ubTS = ts + maxRemaining[depth][0], ubAC = ac + maxRemaining[depth][1], ubHA = ha + maxRemaining[depth][2];
            int ubCats = cats + maxRemaining[depth][3];
            int ubRating = Math.min(calculateRatingFast(physics, ubTS, ubAC, ubHA), targetRating);
            long optScore;
            if (prioOrd == 3) {
                optScore = (long) ubCats * 100000000L + (long) ubRating * 100L;
            } else {
                int ubPrio = prioCats + maxRemPrioCats[depth];
                int pStat; switch (prioOrd) { case 0: pStat = ubTS; break; case 1: pStat = ubAC; break; default: pStat = ubHA; }
                optScore = (long) ubRating * 10000000L + (long) ubPrio * 1000000L + (long) pStat;
            }
            if (optScore <= eff) return;
        }

        int remTS = maxRemaining[depth+1][0], remAC = maxRemaining[depth+1][1], remHA = maxRemaining[depth+1][2];
        int remCats = maxRemaining[depth+1][3], remPrioCats = maxRemPrioCats[depth+1];
        int[][] catParts = partStats[depth];
        boolean isPrio = isPrioCat[depth];

        boolean allExceed = catParts.length > 0 &&
                calculateRatingFast(physics, ts + minPartStats[depth][0], ac + minPartStats[depth][1], ha + minPartStats[depth][2]) > targetRating;

        if (!allExceed) {
            for (int i = 0; i < catParts.length; i++) {
                int nts = ts + catParts[i][0], nac = ac + catParts[i][1], nha = ha + catParts[i][2];
                if (calculateRatingFast(physics, nts, nac, nha) > targetRating) continue;

                if (eff >= 0) {
                    int pUbTS = nts + remTS, pUbAC = nac + remAC, pUbHA = nha + remHA;
                    int pUbR = Math.min(calculateRatingFast(physics, pUbTS, pUbAC, pUbHA), targetRating);
                    long pOpt;
                    if (prioOrd == 3) {
                        pOpt = (long)(cats + 1 + remCats) * 100000000L + (long) pUbR * 100L;
                    } else {
                        int pp = prioCats + (isPrio ? 1 : 0) + remPrioCats;
                        int ps; switch (prioOrd) { case 0: ps = pUbTS; break; case 1: ps = pUbAC; break; default: ps = pUbHA; }
                        pOpt = (long) pUbR * 10000000L + (long) pp * 1000000L + (long) ps;
                    }
                    if (pOpt <= eff) continue;
                }

                choices[depth] = i;
                dfsBatch(physics, partStats, isPrioCat, n, maxRemaining, maxRemPrioCats, minPartStats,
                         choices, bestChoices, bestScore, targetRating, prioOrd, depth + 1,
                         nts, nac, nha, cats + 1, prioCats + (isPrio ? 1 : 0), iterCount, maxIterations);
                if (iterCount[0] > maxIterations) return;
                if (bestScore[0] > eff) eff = bestScore[0];
            }
        }

        // Try skipping this category
        choices[depth] = -1;
        dfsBatch(physics, partStats, isPrioCat, n, maxRemaining, maxRemPrioCats, minPartStats,
                 choices, bestChoices, bestScore, targetRating, prioOrd, depth + 1,
                 ts, ac, ha, cats, prioCats, iterCount, maxIterations);
    }

    /**
     * Calculate rating for a list of parts using the same formula as PerformanceBO.
     */
    private int calculateRatingForParts(CarClassesEntity carClass, List<ProductEntity> parts) {
        int totalTopSpeed = 0, totalAccel = 0, totalHandling = 0;
        for (ProductEntity part : parts) {
            totalTopSpeed += part.getTopSpeed();
            totalAccel += part.getAccel();
            totalHandling += part.getHandling();
        }
        return calculateRating(carClass, totalTopSpeed, totalAccel, totalHandling);
    }

    /**
     * Calculate the car rating from base physics and performance stat totals.
     * This replicates the formula from PerformanceBO.calcNewCarClass.
     */
    private int calculateRating(CarClassesEntity carClass, int topSpeed, int accel, int handling) {
        double tt = topSpeed * 0.0099999998;
        double ta = accel * 0.0099999998;
        double th = handling * 0.0099999998;
        double totalChanges = 1.0 / (((tt + ta + th) * 0.66666669) + 1.0);
        tt = tt * totalChanges;
        ta = ta * totalChanges;
        th = th * totalChanges;
        double finalConstant = 1.0 - tt - ta - th;

        double finalTopSpeed = (finalConstant * carClass.getTsStock().doubleValue())
                + (carClass.getTsVar1().doubleValue() * th)
                + (carClass.getTsVar2().doubleValue() * ta)
                + (carClass.getTsVar3().doubleValue() * tt);

        double finalAccel = (finalConstant * carClass.getAcStock().doubleValue())
                + (carClass.getAcVar1().doubleValue() * th)
                + (carClass.getAcVar2().doubleValue() * ta)
                + (carClass.getAcVar3().doubleValue() * tt);

        double finalHandling = (finalConstant * carClass.getHaStock().doubleValue())
                + (carClass.getHaVar1().doubleValue() * th)
                + (carClass.getHaVar2().doubleValue() * ta)
                + (carClass.getHaVar3().doubleValue() * tt);

        double finalClass = ((int) finalTopSpeed + (int) finalAccel + (int) finalHandling) / 3.0;
        return (int) finalClass;
    }

    private void sendMessage(OpenFireSoapBoxCli openFireSoapBoxCli, Long personaId, String message) {
        openFireSoapBoxCli.send(XmppChat.createSystemMessage(message), personaId);
    }

    private String getAvailableClassNames() {
        return availableClassNamesStr;
    }

    private String findClassNameByRating(int rating) {
        for (CarClassListEntity cls : allClassList) {
            if (rating >= cls.getMinVal() && rating <= cls.getMaxVal()) return cls.getName();
        }
        return "?";
    }

    /**
     * Re-generate all cached setups for a specific car (by fullName).
     * Deletes existing cache entries for that car's physics hash and recalculates.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public String reGenerateForCar(String carName) {
        // Find car by fullName (case-insensitive partial match) using in-memory cache
        String lowerName = carName.toLowerCase();
        List<CarClassesEntity> matchedCars = new ArrayList<>();
        for (CarClassesEntity car : carClassesByHash.values()) {
            if (car.getFullName() != null && car.getFullName().toLowerCase().contains(lowerName)) {
                matchedCars.add(car);
            }
        }
        if (matchedCars.isEmpty()) {
            return "ERROR: No car found matching '" + carName + "'";
        }

        // Collect unique physics hashes for matched cars
        Set<Integer> hashes = new LinkedHashSet<>();
        List<String> matchedNames = new ArrayList<>();
        for (CarClassesEntity car : matchedCars) {
            if (car.getHash() != null && car.getTsStock() != null) {
                hashes.add(car.getHash());
                matchedNames.add(car.getFullName());
            }
        }
        if (hashes.isEmpty()) {
            return "ERROR: Matched cars have no physics data";
        }

        System.out.println("[AutoTune] Re-generating for " + matchedNames.size() + " cars: " + matchedNames);

        // Delete existing cache entries for these physics hashes (DB + memory)
        for (int hash : hashes) {
            autoTuneCacheDAO.deleteByPhysicsHash(hash);
            tuneCache.keySet().removeIf(key -> key.startsWith(hash + "_"));
        }

        // Load parts
        List<ProductEntity> allPerfParts = productDAO.findByLevelEnabled(
                "NFSW_NA_EP_PERFORMANCEPARTS", "PERFORMANCEPART",
                100, true, true, false);
        List<ProductEntity> usableParts = allPerfParts.stream()
                .filter(p -> p.getTopSpeed() > 0 || p.getAccel() > 0 || p.getHandling() > 0)
                .filter(p -> p.getSubType() == null || !p.getSubType().toLowerCase().contains("misc"))
                .sorted(Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed())
                .collect(Collectors.toList());
        if (usableParts.isEmpty()) return "ERROR: No performance parts found";

        Map<String, List<ProductEntity>> partsBySubType = usableParts.stream()
                .collect(Collectors.groupingBy(p -> p.getSubType() != null ? p.getSubType() : "unknown"));

        // Precompute per-priority data
        Map<StatPriority, PrecomputedParts> priorityDataMap = new EnumMap<>(StatPriority.class);
        for (StatPriority prio : StatPriority.values()) {
            priorityDataMap.put(prio, precomputeForPriority(partsBySubType, prio));
        }

        // Global max stats for feasibility check
        int globalMaxTS = 0, globalMaxAC = 0, globalMaxHA = 0;
        for (List<ProductEntity> parts : partsBySubType.values()) {
            int bTS = 0, bAC = 0, bHA = 0;
            for (ProductEntity p : parts) {
                bTS = Math.max(bTS, p.getTopSpeed());
                bAC = Math.max(bAC, p.getAccel());
                bHA = Math.max(bHA, p.getHandling());
            }
            globalMaxTS += bTS; globalMaxAC += bAC; globalMaxHA += bHA;
        }

        List<CarClassListEntity> allClasses = allClassList;

        // Deduplicate by physics hash (skip perfLocked cars)
        Map<Integer, CarClassesEntity> uniqueCars = new LinkedHashMap<>();
        for (CarClassesEntity car : matchedCars) {
            if (car.getHash() != null && car.getTsStock() != null && !car.isPerfLocked()) {
                uniqueCars.putIfAbsent(car.getHash(), car);
            }
        }
        if (uniqueCars.isEmpty()) {
            return "ERROR: Matched cars have no physics data or are performance-locked";
        }

        AtomicInteger generated = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger jobsDone = new AtomicInteger(0);
        int reloadMaxIter = parameterBO.getIntParam("SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS", DEFAULT_RELOAD_MAX_ITERATIONS);

        List<Runnable> jobs = new ArrayList<>();
        for (Map.Entry<Integer, CarClassesEntity> entry : uniqueCars.entrySet()) {
            CarClassesEntity car = entry.getValue();
            double[] physics = cacheCarPhysics(car);
            int maxRating = calculateRatingFast(physics, globalMaxTS, globalMaxAC, globalMaxHA);

            for (CarClassListEntity cls : allClasses) {
                if (maxRating < cls.getMinVal()) {
                    skippedCount.addAndGet(StatPriority.values().length);
                    continue;
                }
                int effectiveTarget = Math.min(cls.getMaxVal(), maxRating);
                int clsMinVal = cls.getMinVal();

                for (StatPriority priority : StatPriority.values()) {
                    PrecomputedParts pp = priorityDataMap.get(priority);
                    final int fTarget = effectiveTarget;
                    final int fMinRating = clsMinVal;
                    final String fClsName = cls.getName();
                    final String fPrioName = priority.name();
                    final int fHash = car.getHash();

                    jobs.add(() -> {
                        int[] choices = findOptimalPartsBatch(physics, pp, fTarget, reloadMaxIter);
                        if (choices != null) {
                            List<ProductEntity> result = new ArrayList<>();
                            int rts = 0, rac = 0, rha = 0;
                            int maxPartLevel = 0;
                            for (int i = 0; i < pp.n; i++) {
                                if (choices[i] >= 0) {
                                    ProductEntity part = pp.partLists.get(i).get(choices[i]);
                                    result.add(part);
                                    rts += pp.partStats[i][choices[i]][0];
                                    rac += pp.partStats[i][choices[i]][1];
                                    rha += pp.partStats[i][choices[i]][2];
                                    if (part.getLevel() > maxPartLevel) maxPartLevel = part.getLevel();
                                }
                            }
                            int achievedRating = calculateRatingFast(physics, rts, rac, rha);
                            if (achievedRating >= fMinRating && !result.isEmpty()) {
                                String cachePrefix = fHash + "_" + fClsName + "_" + fPrioName;
                                tuneCache.computeIfAbsent(cachePrefix, k -> new TreeMap<>()).put(maxPartLevel, new CachedTuneSetup(result, achievedRating));
                                StringBuilder hashesStr = new StringBuilder();
                                for (ProductEntity p : result) {
                                    if (hashesStr.length() > 0) hashesStr.append(",");
                                    hashesStr.append(p.getHash());
                                }
                                AutoTuneCacheEntity entity = new AutoTuneCacheEntity();
                                entity.setPhysicsHash(fHash);
                                entity.setClassName(fClsName);
                                entity.setPriority(fPrioName);
                                entity.setLevel(maxPartLevel);
                                entity.setPartHashes(hashesStr.toString());
                                entity.setAchievedRating(achievedRating);
                                autoTuneCacheDAO.insertNewTx(entity);
                                generated.incrementAndGet();
                            } else {
                                skippedCount.incrementAndGet();
                            }
                        } else {
                            skippedCount.incrementAndGet();
                        }
                        jobsDone.incrementAndGet();
                    });
                }
            }
        }

        int totalJobs = jobs.size();
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable job : jobs) {
            futures.add(DFS_EXECUTOR.submit(job));
        }
        long startTime = System.currentTimeMillis();
        for (Future<?> future : futures) {
            try { future.get(); } catch (Exception e) {
                System.out.println("[AutoTune] Job error: " + e.getMessage());
            }
        }
        long totalTime = (System.currentTimeMillis() - startTime) / 1000;

        System.out.println("[AutoTune] Re-generated for '" + carName + "' in " + totalTime + "s: "
                + generated.get() + " generated, " + skippedCount.get() + " skipped");

        return "SUCCESS! Re-generated " + generated.get() + " setups for '" + carName
                + "' (" + matchedNames.size() + " cars, " + uniqueCars.size() + " unique physics) in " + totalTime + "s";
    }

    /**
     * Pre-generate optimal tune setups for every car x class x priority combination.
     * Results are stored in the in-memory cache for instant /tune lookups.
     * Called via the /ReloadAutoTune admin API endpoint.
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public String preGenerateAllSetups() {
        return doPreGenerateAllSetups();
    }

    private String doPreGenerateAllSetups() {
        // Load existing cache keys from DB to resume where we left off (lightweight)
        Set<String> existingKeys = loadCacheKeysFromDB();

        // Load all parts at max level (covers all players)
        List<ProductEntity> allPerfParts = productDAO.findByLevelEnabled(
                "NFSW_NA_EP_PERFORMANCEPARTS", "PERFORMANCEPART",
                100, true, true, false);

        List<ProductEntity> usableParts = allPerfParts.stream()
                .filter(p -> p.getTopSpeed() > 0 || p.getAccel() > 0 || p.getHandling() > 0)
                .filter(p -> p.getSubType() == null || !p.getSubType().toLowerCase().contains("misc"))
                .sorted(Comparator.comparingInt((ProductEntity p) -> p.getTopSpeed() + p.getAccel() + p.getHandling()).reversed())
                .collect(Collectors.toList());

        if (usableParts.isEmpty()) return "ERROR: No performance parts found";

        Map<String, List<ProductEntity>> partsBySubType = usableParts.stream()
                .collect(Collectors.groupingBy(p -> p.getSubType() != null ? p.getSubType() : "unknown"));

        // Precompute per-priority data ONCE (sort, dedup, partStats, maxRemaining, etc.)
        Map<StatPriority, PrecomputedParts> priorityDataMap = new EnumMap<>(StatPriority.class);
        for (StatPriority prio : StatPriority.values()) {
            priorityDataMap.put(prio, precomputeForPriority(partsBySubType, prio));
        }

        // Compute true global max stats (per-stat max across all parts per category)
        int globalMaxTS = 0, globalMaxAC = 0, globalMaxHA = 0;
        for (List<ProductEntity> parts : partsBySubType.values()) {
            int bTS = 0, bAC = 0, bHA = 0;
            for (ProductEntity p : parts) {
                bTS = Math.max(bTS, p.getTopSpeed());
                bAC = Math.max(bAC, p.getAccel());
                bHA = Math.max(bHA, p.getHandling());
            }
            globalMaxTS += bTS; globalMaxAC += bAC; globalMaxHA += bHA;
        }

        List<CarClassesEntity> allCars = new ArrayList<>(carClassesByHash.values());
        List<CarClassListEntity> allClasses = allClassList;

        // Deduplicate cars by physics hash — many car models share identical physics
        // Skip perfLocked cars (performance modifications not allowed)
        Map<Integer, CarClassesEntity> uniqueCars = new LinkedHashMap<>();
        Map<Integer, List<String>> carNamesByHash = new LinkedHashMap<>();
        for (CarClassesEntity car : allCars) {
            if (car.getHash() != null && car.getTsStock() != null && !car.isPerfLocked()) {
                uniqueCars.putIfAbsent(car.getHash(), car);
                carNamesByHash.computeIfAbsent(car.getHash(), k -> new ArrayList<>()).add(car.getFullName());
            }
        }

        AtomicInteger generated = new AtomicInteger(0);
        AtomicInteger skippedCount = new AtomicInteger(0);
        AtomicInteger existingCount = new AtomicInteger(0);
        AtomicInteger jobsDone = new AtomicInteger(0);
        int reloadMaxIter = parameterBO.getIntParam("SBRWR_AUTOTUNE_RELOAD_MAX_ITERATIONS", DEFAULT_RELOAD_MAX_ITERATIONS);

        // Build all (physicsHash, class, priority) jobs
        List<Runnable> jobs = new ArrayList<>();
        for (Map.Entry<Integer, CarClassesEntity> entry : uniqueCars.entrySet()) {
            CarClassesEntity car = entry.getValue();
            double[] physics = cacheCarPhysics(car);
            int maxRating = calculateRatingFast(physics, globalMaxTS, globalMaxAC, globalMaxHA);

            for (CarClassListEntity cls : allClasses) {
                if (maxRating < cls.getMinVal()) {
                    skippedCount.addAndGet(StatPriority.values().length);
                    continue;
                }
                int effectiveTarget = Math.min(cls.getMaxVal(), maxRating);
                int clsMinVal = cls.getMinVal();

                for (StatPriority priority : StatPriority.values()) {
                    String cachePrefix = car.getHash() + "_" + cls.getName() + "_" + priority.name() + "_";
                    // Skip if any cache entry exists for this hash/class/priority
                    boolean found = false;
                    for (String k : existingKeys) {
                        if (k.startsWith(cachePrefix)) { found = true; break; }
                    }
                    if (found) {
                        existingCount.incrementAndGet();
                        continue;
                    }

                    PrecomputedParts pp = priorityDataMap.get(priority);
                    final int fTarget = effectiveTarget;
                    final int fMinRating = clsMinVal;
                    final String fClsName = cls.getName();
                    final String fPrioName = priority.name();
                    final int fHash = car.getHash();

                    jobs.add(() -> {
                        int[] choices = findOptimalPartsBatch(physics, pp, fTarget, reloadMaxIter);

                        if (choices != null) {
                            List<ProductEntity> result = new ArrayList<>();
                            int rts = 0, rac = 0, rha = 0;
                            int maxPartLevel = 0;
                            for (int i = 0; i < pp.n; i++) {
                                if (choices[i] >= 0) {
                                    ProductEntity part = pp.partLists.get(i).get(choices[i]);
                                    result.add(part);
                                    rts += pp.partStats[i][choices[i]][0];
                                    rac += pp.partStats[i][choices[i]][1];
                                    rha += pp.partStats[i][choices[i]][2];
                                    if (part.getLevel() > maxPartLevel) maxPartLevel = part.getLevel();
                                }
                            }
                            int achievedRating = calculateRatingFast(physics, rts, rac, rha);

                            if (achievedRating >= fMinRating && !result.isEmpty()) {
                                String memKey = fHash + "_" + fClsName + "_" + fPrioName;
                                tuneCache.computeIfAbsent(memKey, k -> new TreeMap<>()).put(maxPartLevel, new CachedTuneSetup(result, achievedRating));

                                StringBuilder hashesStr = new StringBuilder();
                                for (ProductEntity p : result) {
                                    if (hashesStr.length() > 0) hashesStr.append(",");
                                    hashesStr.append(p.getHash());
                                }
                                AutoTuneCacheEntity entity = new AutoTuneCacheEntity();
                                entity.setPhysicsHash(fHash);
                                entity.setClassName(fClsName);
                                entity.setPriority(fPrioName);
                                entity.setLevel(maxPartLevel);
                                entity.setPartHashes(hashesStr.toString());
                                entity.setAchievedRating(achievedRating);
                                autoTuneCacheDAO.insertNewTx(entity);

                                generated.incrementAndGet();
                            } else {
                                skippedCount.incrementAndGet();
                            }
                        } else {
                            skippedCount.incrementAndGet();
                        }
                        jobsDone.incrementAndGet();
                    });
                }
            }
        }

        int totalJobs = jobs.size();
        System.out.println("[AutoTune] Starting " + totalJobs + " jobs across " + uniqueCars.size()
                + " unique physics profiles (from " + allCars.size() + " cars), "
                + allClasses.size() + " classes, " + existingKeys.size() + " already cached...");

        // Submit all jobs to ForkJoinPool — each job runs serial DFS, pool handles parallelism
        List<Future<?>> futures = new ArrayList<>();
        for (Runnable job : jobs) {
            futures.add(DFS_EXECUTOR.submit(job));
        }

        // Wait with progress logging
        long startTime = System.currentTimeMillis();
        int lastLogged = 0;
        for (int i = 0; i < futures.size(); i++) {
            try { futures.get(i).get(); } catch (Exception e) {
                System.out.println("[AutoTune] Job error: " + e.getMessage());
            }
            int done = jobsDone.get();
            if (done - lastLogged >= 200 || i == futures.size() - 1) {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                System.out.println("[AutoTune] Progress: " + done + "/" + totalJobs
                        + " (" + generated.get() + " generated, " + elapsed + "s elapsed)");
                lastLogged = done;
            }
        }

        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("[AutoTune] DONE in " + totalTime + "s! Generated: " + generated.get()
                + " new, " + existingCount.get() + " existing, " + skippedCount.get() + " skipped");

        return "SUCCESS! Generated " + generated.get() + " new setups (" + existingCount.get()
                + " already cached, " + skippedCount.get() + " skipped) for "
                + uniqueCars.size() + " unique physics profiles x " + allClasses.size()
                + " classes in " + totalTime + "s (persisted to DB)";
    }
}
