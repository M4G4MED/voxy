package me.cortex.voxy.client.core.distant;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.cortex.voxy.client.config.DistantTerrainConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Drives terrain filling around the player beyond the loaded view distance.
 *  Work is tracked as individual chunks in two distance-priority queues:
 *    read queue:  chunks whose region file exists in the local save (read & ingest)
 *    synth queue: chunks never saved (approximated from the world's own noise+surface)
 *  Both queues always pop the chunk nearest to the player first, so filling
 *  grows as a circle outward from the edge of the normal render distance
 *  instead of advancing region-file by region-file or in any fixed direction.
 *  When the player moves the queues are re-prioritized against the new center.
 *  Singleplayer only: requires the integrated server and a locally readable
 *  save directory. Multiplayer is intentionally out of scope for now. */
public class DistantTerrainManager {
    private static volatile DistantTerrainManager ACTIVE = null;

    /** Upper bound on queued chunk jobs per phase; excess (furthest) entries are dropped. */
    private static final int MAX_QUEUE = 65536;//fits a whole 96-radius ring (~29k chunks) so trim/requeue churn never fires

    private record Job(long distSq, long key) {}

    private final WorldIdentifier worldId;
    private final ResourceKey<Level> dimension;
    private final File regionDirectory;
    private final WorldEngine engine;

    private final RegionCatalog catalog = new RegionCatalog();
    private volatile boolean catalogScanned = false;
    private int passesSinceScan = 0;

    private final Object stateLock = new Object();
    // Chunks finished by either phase (or intentionally dropped), never re-queued
    private final LongOpenHashSet doneChunks = new LongOpenHashSet();
    // Chunks whose region file turned out not to contain them: the read phase can
    // never satisfy these, so they stay synth-only and are never offered to the
    // read queue again (without this they churn read->synth->read every pass).
    private final LongOpenHashSet synthOnlyChunks = new LongOpenHashSet();
    // Chunks currently sitting in a queue or in flight
    private final LongOpenHashSet queuedChunks = new LongOpenHashSet();

    private final PriorityBlockingQueue<Job> readQueue = new PriorityBlockingQueue<>(512, Comparator.comparingLong(Job::distSq));
    private final PriorityBlockingQueue<Job> synthQueue = new PriorityBlockingQueue<>(512, Comparator.comparingLong(Job::distSq));
    private final AtomicBoolean readWorkerActive = new AtomicBoolean(false);
    private final AtomicInteger synthWorkersActive = new AtomicInteger(0);
    private static final int SYNTH_WORKER_COUNT = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 4));
    private volatile boolean stopping = false;

    private int centerCX = Integer.MIN_VALUE;
    private int centerCZ = Integer.MIN_VALUE;

    private long lastPassMs = 0;
    private long lastStatsLogMs = 0;

    //Stats for the debug overlay (adder: touched from worker + render threads)
    public volatile long readQueuedTotal = 0;
    public final LongAdder synthQueuedTotal = new LongAdder();
    public volatile long readDone = 0;
    public final LongAdder synthDone = new LongAdder();
    public final LongAdder synthFailed = new LongAdder();

    private DistantTerrainManager(WorldIdentifier worldId, ResourceKey<Level> dimension, File regionDirectory, WorldEngine engine) {
        this.worldId = worldId;
        this.dimension = dimension;
        this.regionDirectory = regionDirectory;
        this.engine = engine;
    }

    /** Called from ClientLevel tick (server thread in singleplayer). */
    public static void onClientLevelTick(net.minecraft.client.multiplayer.ClientLevel level) {
        var conf = DistantTerrainConfig.get();
        var mc = Minecraft.getInstance();
        if (!conf.enabled || level == null || mc.player == null) {
            stopActive();
            return;
        }
        if (mc.isPaused()) {
            return;//paused, do not tear down state, just idle
        }
        // Singleplayer gate: no remote servers get any of this machinery
        if (!mc.isLocalServer()) {
            stopActive();
            return;
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            stopActive();
            return;
        }
        var id = WorldIdentifier.of(level);
        var engine = id == null ? null : id.getNullable();
        if (engine == null || !engine.isLive()) {
            return;
        }

        DistantTerrainManager mgr = ACTIVE;
        if (mgr == null || !mgr.worldId.equals(id) || !mgr.engine.isLive() || mgr.stopping) {
            stopActive();
            mgr = new DistantTerrainManager(id, level.dimension(), resolveRegionDir(server, level.dimension()), engine);
            ACTIVE = mgr;
        }
        mgr.pass(server, conf, mc.player.blockPosition());
    }

    private static File resolveRegionDir(MinecraftServer server, ResourceKey<Level> dim) {
        try {
            return DimensionType.getStorageFolder(dim, server.getWorldPath(LevelResource.ROOT)).resolve("region").toFile();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Inner edge of the filled ring, in chunks: tracks the player's actual
     *  render distance so LoDs start right where normal chunks end. */
    private static int currentViewRadiusChunks() {
        try {
            int viewDist = Minecraft.getInstance().options.getEffectiveRenderDistance();
            // +1 chunk of slack so the ring's first chunk is fully outside the
            // loaded box even at the worst corner offset of the square view.
            return viewDist + 1;
        } catch (Throwable t) {
            return 12;
        }
    }

    private void pass(MinecraftServer server, DistantTerrainConfig conf, BlockPos playerPos) {
        long now = System.currentTimeMillis();
        if (now - lastPassMs < 500) {
            return;
        }
        lastPassMs = now;

        ServerLevel serverLevel = server.getLevel(this.dimension);
        if (serverLevel == null) {
            return;
        }

        int pcx = playerPos.getX() >> 4;
        int pcz = playerPos.getZ() >> 4;

        // ---- Keep the region catalog fresh (newly saved chunks become read jobs) ----
        if (conf.read_saved_terrain && regionDirectory != null && regionDirectory.isDirectory()) {
            if (!catalogScanned || ++passesSinceScan >= 60) {
                passesSinceScan = 0;
                catalog.scanDirectory(regionDirectory.toPath());
                catalogScanned = true;
            }
        }

        // ---- Re-prioritize both queues when the player moves (center update) ----
        if (pcx != centerCX || pcz != centerCZ) {
            centerCX = pcx;
            centerCZ = pcz;
            recenterQueues(pcx, pcz);
        }

        int margin = currentViewRadiusChunks() + Math.max(0, conf.margin_chunks);
        int radius = Math.max(margin + 16, conf.radius_chunks);

        // ---- Queue every not-yet-done chunk in the ring; priority does the ordering ----
        enqueueRing(pcx, pcz, radius, margin, conf);
        trimQueue(readQueue);
        trimQueue(synthQueue);

        ensureWorker(readWorkerActive, () -> readLoop(serverLevel));
        ensureSynthWorkers(serverLevel);

        if (now - lastStatsLogMs > 5000) {
            lastStatsLogMs = now;
            Logger.info("Distant terrain stats: read_queued=" + readQueuedTotal + " read_done=" + readDone
                    + " synth_queued=" + synthQueuedTotal.sum() + " synth_done=" + synthDone.sum()
                    + " synth_failed=" + synthFailed.sum()
                    + " pend_r=" + readQueue.size() + " pend_s=" + synthQueue.size()
                    + " workers=" + synthWorkersActive.get() + "/" + SYNTH_WORKER_COUNT
                    + " center=" + pcx + "," + pcz + " inner=" + margin);
        }
    }

    private void recenterQueues(int pcx, int pcz) {
        synchronized (stateLock) {
            recenterQueue(readQueue, pcx, pcz);
            recenterQueue(synthQueue, pcx, pcz);
        }
    }

    private static void recenterQueue(PriorityBlockingQueue<Job> queue, int pcx, int pcz) {
        List<Job> drained = new ArrayList<>(queue);
        queue.clear();
        for (Job j : drained) {
            int cx = RegionCatalog.unpackX(j.key());
            int cz = RegionCatalog.unpackZ(j.key());
            queue.add(new Job(distSq(cx, cz, pcx, pcz), j.key()));
        }
    }

    private static long distSq(int cx, int cz, int ox, int oz) {
        long dx = (long) cx - ox;
        long dz = (long) cz - oz;
        return dx * dx + dz * dz;
    }

    private void enqueueRing(int pcx, int pcz, int radius, int margin, DistantTerrainConfig conf) {
        long r2 = (long) radius * radius;
        boolean readSaved = conf.read_saved_terrain && catalogScanned && regionDirectory != null;
        int enqueued = 0;
        synchronized (stateLock) {
            // Walk rings of increasing radius so the per-pass budget is spent on the
            // nearest chunks first; a row-major sweep would burn it all on one side.
            // The hole is a CIRCLE (matching the round visible edge of the loaded
            // view, which fog/far-plane cut radially), not a square: a square hole
            // leaves four empty corner wedges on the diagonals in a fresh world.
            long innerR2 = (long) margin * margin;
            int maxCheb = Math.max(margin, radius);
            for (int cheb = 1; cheb <= maxCheb && enqueued < 512; cheb++) {
                // Square ring at Chebyshev radius == cheb (the visible "shell" order),
                // but the queue's distance key does the real radial ordering.
                for (int i = 0; i < cheb * 8; i++) {
                    int side = i / (cheb * 2);
                    int t = i % (cheb * 2);
                    int dx, dz;
                    switch (side) {
                        case 0 -> { dx = -cheb + t; dz = -cheb; }
                        case 1 -> { dx = cheb; dz = -cheb + t; }
                        case 2 -> { dx = cheb - t; dz = cheb; }
                        default -> { dx = -cheb; dz = cheb - t; }
                    }
                    long d2 = (long) dx * dx + (long) dz * dz;
                    if (d2 > r2 || d2 < innerR2) {
                        continue;
                    }
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    long key = RegionCatalog.pack(cx, cz);
                    if (doneChunks.contains(key) || queuedChunks.contains(key)) {
                        continue;
                    }
                    boolean saved = readSaved && catalog.hasRegion(cx >> 5, cz >> 5) && !synthOnlyChunks.contains(key);
                    if (!saved && !conf.synthesize_unsaved_terrain) {
                        continue;
                    }
                    queuedChunks.add(key);
                    if (saved) {
                        readQueue.add(new Job(d2, key));
                        readQueuedTotal++;
                    } else {
                        synthQueue.add(new Job(d2, key));
                        synthQueuedTotal.increment();
                    }
                    enqueued++;
                }
            }
        }
    }

    /** Drop the furthest entries until the queue fits, freeing them for re-queue later. */
    private void trimQueue(PriorityBlockingQueue<Job> queue) {
        synchronized (stateLock) {
            if (queue.size() <= MAX_QUEUE) {
                return;
            }
            List<Job> all = new ArrayList<>(queue);
            queue.clear();
            all.sort(Comparator.comparingLong(Job::distSq));
            for (int i = 0; i < MAX_QUEUE && i < all.size(); i++) {
                queue.add(all.get(i));
            }
            for (int i = MAX_QUEUE; i < all.size(); i++) {
                queuedChunks.remove(all.get(i).key());//allow re-queue if player comes back
            }
        }
    }

    private void markChunkDone(long key) {
        synchronized (stateLock) {
            queuedChunks.remove(key);
            doneChunks.add(key);
        }
    }

    private void markChunkFailed(long key) {
        synchronized (stateLock) {
            queuedChunks.remove(key);//retry eligible next pass
        }
    }

    private Job popNearest(PriorityBlockingQueue<Job> queue) {
        synchronized (stateLock) {
            Job j = queue.poll();
            if (j != null && doneChunks.contains(j.key())) {
                //stale entry (its region was already read as a whole); skip it
                queuedChunks.remove(j.key());
                return null;
            }
            return j;
        }
    }

    private void ensureWorker(AtomicBoolean flag, Runnable body) {
        if (stopping || !flag.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(body, "Distant terrain region reader");
        t.setDaemon(true);
        // NORM priority (with per-chunk pacing) on purpose: MIN_PRIORITY workers get
        // starved by the saturated render thread once the LoD scene is large.
        t.setPriority(Thread.NORM_PRIORITY);
        t.start();
    }

    /** Synth is pure CPU worldgen, safe to run several workers side by side;
     *  the shared priority queue hands each worker the nearest remaining chunk. */
    private void ensureSynthWorkers(ServerLevel serverLevel) {
        if (stopping || synthQueue.isEmpty()) {
            return;
        }
        while (true) {
            int cur = synthWorkersActive.get();
            if (cur >= SYNTH_WORKER_COUNT || synthWorkersActive.compareAndSet(cur, cur + 1)) {
                if (cur >= SYNTH_WORKER_COUNT) {
                    return;
                }
                Thread t = new Thread(() -> synthLoop(serverLevel), "Distant terrain synth " + cur);
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY);
                t.start();
            }
        }
    }

    private boolean stillValid(ServerLevel serverLevel) {
        return !stopping && engine.isLive() && serverLevel.getServer().isRunning();
    }

    // ---- Read phase: single chunks read by random access, nearest first ----
    private void readLoop(ServerLevel serverLevel) {
        boolean refAcquired = false;
        try {
            if (!stillValid(serverLevel)) {
                return;
            }
            engine.acquireRef();
            refAcquired = true;
            try (var reader = new RegionChunkReader(engine, serverLevel, regionDirectory)) {
                while (stillValid(serverLevel)) {
                    Job job = popNearest(readQueue);
                    if (job == null) {
                        if (readQueue.isEmpty()) {
                            return;
                        }
                        continue;
                    }
                    int cx = RegionCatalog.unpackX(job.key());
                    int cz = RegionCatalog.unpackZ(job.key());
                    int outcome;
                    try {
                        outcome = reader.readChunkAndIngest(cx, cz);
                    } catch (Throwable th) {
                        Logger.error("Distant terrain: region read failed for chunk " + cx + "," + cz, th);
                        outcome = -1;
                    }
                    if (outcome == 1) {
                        readDone++;
                        markChunkDone(job.key());
                    } else if (outcome == 0) {
                        // Region file exists but this specific chunk was never saved there:
                        // mark it synth-only (never offered to the read queue again) and
                        // hand it to the synth phase instead.
                        boolean queueSynth;
                        synchronized (stateLock) {
                            synthOnlyChunks.add(job.key());
                            queuedChunks.remove(job.key());
                            queueSynth = DistantTerrainConfig.get().synthesize_unsaved_terrain
                                    && !doneChunks.contains(job.key()) && !queuedChunks.contains(job.key());
                            if (queueSynth) {
                                queuedChunks.add(job.key());
                                synthQueue.add(new Job(distSq(cx, cz, centerCX, centerCZ), job.key()));
                                synthQueuedTotal.increment();
                            }
                        }
                    } else {
                        // IO error: drop from tracking so the next pass may retry
                        markChunkFailed(job.key());
                    }
                }
            }
        } catch (Throwable t) {
            if (!stopping) {
                Logger.error("Distant terrain: read worker crashed", t);
            }
        } finally {
            readWorkerActive.set(false);
            if (refAcquired && engine.isLive()) {
                try {
                    engine.releaseRef();
                } catch (Throwable ignored) {
                }
            }
            if (!stopping && !readQueue.isEmpty() && engine.isLive()) {
                ensureWorker(readWorkerActive, () -> readLoop(serverLevel));
            }
        }
    }

    // ---- Synth phase: never-saved chunks approximated from the world's own generation ----
    private void synthLoop(ServerLevel serverLevel) {
        boolean refAcquired = false;
        try {
            if (!stillValid(serverLevel)) {
                return;
            }
            engine.acquireRef();
            refAcquired = true;
            while (stillValid(serverLevel)) {
                Job job = popNearest(synthQueue);
                if (job == null) {
                    if (synthQueue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                int cx = RegionCatalog.unpackX(job.key());
                int cz = RegionCatalog.unpackZ(job.key());
                try {
                    // No server-chunk-map queries here: ChunkMap internals are not safe to read
                    // off-thread. If the chunk happens to be loading, normal ingest simply
                    // overwrites this approximation when it lands.
                    if (SurfaceSynth.synthAndIngest(serverLevel, engine, cx, cz)) {
                        synthDone.increment();
                        markChunkDone(job.key());
                    } else {
                        //Synth or ingest did not complete; do NOT mark done or the
                        //chunk is permanently missing (a hole that never heals).
                        synthFailed.increment();
                        markChunkFailed(job.key());
                    }
                } catch (Throwable th) {
                    Logger.error("Distant terrain: synth failed for " + cx + "," + cz, th);
                    markChunkFailed(job.key());
                }
                // pacing: tiny yield so gameplay threads always win scheduling
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        } catch (Throwable t) {
            // Surface worker deaths: without this they vanish silently off the JVM's
            // default handler (which goes to the launcher console, not the game log).
            if (!stopping) {
                Logger.error("Distant terrain: synth worker died", t);
            }
        } finally {
            synthWorkersActive.decrementAndGet();
            if (refAcquired && engine.isLive()) {
                try {
                    engine.releaseRef();
                } catch (Throwable ignored) {
                }
            }
            if (!stopping && !synthQueue.isEmpty() && engine.isLive()) {
                ensureSynthWorkers(serverLevel);
            }
        }
    }

    public static void stopActive() {
        DistantTerrainManager mgr;
        synchronized (DistantTerrainManager.class) {
            mgr = ACTIVE;
            ACTIVE = null;
        }
        if (mgr != null) {
            mgr.stop();
        }
    }

    private void stop() {
        stopping = true;
        readQueue.clear();
        synthQueue.clear();
        long deadline = System.currentTimeMillis() + 3000;
        while ((readWorkerActive.get() || synthWorkersActive.get() > 0) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    public static void addDebugLines(List<String> out) {
        var conf = DistantTerrainConfig.get();
        if (!conf.enabled) {
            out.add("Distant terrain: disabled (config)");
            return;
        }
        var mgr = ACTIVE;
        if (mgr == null) {
            out.add("Distant terrain: active=no (singleplayer required)");
            return;
        }
        out.add("Distant terrain: read " + mgr.readDone + "/" + mgr.readQueuedTotal
                + " synth " + mgr.synthDone.sum() + "/" + mgr.synthQueuedTotal.sum()
                + " pend r=" + mgr.readQueue.size() + " s=" + mgr.synthQueue.size());
    }

    // Accessors kept for diagnostics
    public WorldIdentifier worldId() { return worldId; }
    public int queueDepth() { return readQueue.size() + synthQueue.size(); }
}
