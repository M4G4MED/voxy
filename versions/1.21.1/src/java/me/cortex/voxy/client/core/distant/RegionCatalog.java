package me.cortex.voxy.client.core.distant;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers which region files the client can read directly from disk.
 *  Only valid when the world's save directory is locally accessible. */
public class RegionCatalog {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(\\-?\\d+)\\.(\\-?\\d+)\\.mca$");

    private final LongOpenHashSet presentRegions = new LongOpenHashSet();
    private final LongOpenHashSet scannedRegions = new LongOpenHashSet();

    public static boolean usableDirectory(Path dir) {
        return dir != null && Files.isDirectory(dir);
    }

    public void scanDirectory(Path dir) {
        try (var stream = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            stream.forEach(files::add);
            for (Path p : files) {
                Matcher m = REGION_NAME.matcher(p.getFileName().toString());
                if (m.matches()) {
                    try {
                        int rx = Integer.parseInt(m.group(1));
                        int rz = Integer.parseInt(m.group(2));
                        this.presentRegions.add(pack(rx, rz));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Region coordinates of a chunk, or Long.MIN_VALUE if no local region file exists there. */
    public long regionForChunk(int chunkX, int chunkZ) {
        if (this.presentRegions.isEmpty()) {
            return Long.MIN_VALUE;
        }
        long region = pack(chunkX >> 5, chunkZ >> 5);
        return this.presentRegions.contains(region) ? region : Long.MIN_VALUE;
    }

    public boolean hasRegion(int regionX, int regionZ) {
        return this.presentRegions.contains(pack(regionX, regionZ));
    }

    public boolean regionScanned(int regionX, int regionZ) {
        return this.scannedRegions.contains(pack(regionX, regionZ));
    }

    public void markRegionScanned(int regionX, int regionZ) {
        this.scannedRegions.add(pack(regionX, regionZ));
    }

    public void forgetScans() {
        this.scannedRegions.clear();
    }

    public LongSet scannedRegionsRef() {
        return this.scannedRegions;
    }

    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }
}
