package me.cortex.voxy.client.core.distant;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.importers.ChunkNBTIngestor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/** Reads single chunks out of the world's own region files by positional random
 *  access, without ever importing a whole region at once. Region headers are read
 *  fresh on every lookup so chunks saved moments ago are picked up immediately.
 *  Keeps one open channel per recently used region; used from one background thread. */
public class RegionChunkReader implements AutoCloseable {
    private static final int HEADER_SIZE = 8192;
    private static final int MAX_OPEN_REGIONS = 32;

    private final ChunkNBTIngestor ingestor;
    private final File regionDirectory;

    // Access-ordered so the oldest-used entry is first when trimming
    private final java.util.LinkedHashMap<Long, OpenRegion> openRegions = new java.util.LinkedHashMap<>(16, 0.75f, true);

    private static class OpenRegion {
        final FileChannel channel;
        final long headerAddress;
        final long scratchAddress;//small staging area for per-chunk record headers

        OpenRegion(FileChannel channel) {
            this.channel = channel;
            this.headerAddress = MemoryUtil.nmemAlloc(HEADER_SIZE);
            this.scratchAddress = MemoryUtil.nmemAlloc(16);
        }

        void free() {
            try {
                this.channel.close();
            } catch (IOException ignored) {
            }
            MemoryUtil.nmemFree(this.headerAddress);
            MemoryUtil.nmemFree(this.scratchAddress);
        }
    }

    public RegionChunkReader(WorldEngine engine, ServerLevel level, File regionDirectory) {
        this.ingestor = new ChunkNBTIngestor(engine, level);
        this.regionDirectory = regionDirectory;
    }

    /** Reads and ingests one saved chunk.
     *  @return 1 when the chunk was found and ingested, 0 when the region file parsed
     *  fine but does not contain usable terrain for this chunk (let the synthesizer
     *  handle it), 2 when the entry exists but holds only a mid-generation snapshot
     *  (retry on a later pass, do NOT synthesize over it), -1 on a read/IO error. */
    public int readChunkAndIngest(int chunkX, int chunkZ) {
        int regionX = chunkX >> 5;
        int regionZ = chunkZ >> 5;
        long regionKey = RegionCatalog.pack(regionX, regionZ);
        OpenRegion region = null;
        boolean regionBroken = false;
        try {
            region = openRegions.get(regionKey);
            if (region == null) {
                File file = new File(this.regionDirectory, "r." + regionX + "." + regionZ + ".mca");
                if (!file.isFile() || file.length() < HEADER_SIZE) {
                    return 0;
                }
                region = new OpenRegion(FileChannel.open(file.toPath(), StandardOpenOption.READ));
                openRegions.put(regionKey, region);
                trimOpenRegions();
            }

            int index = (chunkZ & 31) << 5 | (chunkX & 31);
            // Region location table: first 4 KiB of the file, BE ints of (sector<<8)|count.
            // Read fresh per lookup: one small positional read, and it stays current
            // against chunks the game saves while we walk the ring.
            readFully(region.channel, region.headerAddress, 0, HEADER_SIZE);
            int sectorMeta = Integer.reverseBytes(MemoryUtil.memGetInt(region.headerAddress + index * 4));
            int sectorStart = sectorMeta >>> 8;
            int sectorCount = sectorMeta & 0xFF;
            if (sectorMeta == 0 || sectorCount == 0) {
                return 0;//not saved in this region
            }

            long base = sectorStart * 4096L;
            if ((sectorStart + sectorCount - 1) * 4096L + 5 > region.channel.size()) {
                throw new IOException("Region entry points out of bounds");
            }

            // Chunk record: BE int length, byte compression, then the compressed stream.
            byte[] recordHeader = new byte[5];
            readFully(region.channel, region.scratchAddress, base, 5, recordHeader);
            int streamLength = ((recordHeader[0] & 0xFF) << 24 | (recordHeader[1] & 0xFF) << 16
                    | (recordHeader[2] & 0xFF) << 8 | (recordHeader[3] & 0xFF)) - 1;
            byte compression = recordHeader[4];
            if (streamLength <= 0 || streamLength > sectorCount * 4096L - 5) {
                throw new IOException("Chunk stream size out of range: " + streamLength);
            }

            MemoryBuffer data = new MemoryBuffer(streamLength);
            try {
                readFully(region.channel, data.address, base + 5, streamLength);
                try (var decompressed = ChunkNBTIngestor.decompress(compression, data)) {
                    if (decompressed == null) {
                        Logger.error("Distant terrain: error decompressing chunk " + chunkX + "," + chunkZ);
                        return -1;
                    }
                    CompoundTag nbt = NbtIo.read(decompressed);
                    return this.ingestor.ingestChunkNBT(nbt, regionX, regionZ) ? 1 : 0;
                }
            } finally {
                data.free();
            }
        } catch (Throwable t) {
            if (!(t instanceof IOException)) {
                Logger.error("Distant terrain: failed reading chunk " + chunkX + "," + chunkZ, t);
            }
            regionBroken = true;
            return -1;
        } finally {
            if (regionBroken) {
                var broken = openRegions.remove(regionKey);
                if (broken != null) {
                    broken.free();
                }
            }
        }
    }

    /** Positional read of exactly `length` bytes into native memory. */
    private static void readFully(FileChannel channel, long address, long position, long length) throws IOException {
        long done = 0;
        while (done < length) {
            MemoryBuffer view = MemoryBuffer.createUntrackedUnfreeableRawFrom(address + done, length - done);
            int r = channel.read(view.asByteBuffer(), position + done);
            if (r <= 0) {
                throw new IOException("Short read on region file");
            }
            done += r;
        }
    }

    private static void readFully(FileChannel channel, long address, long position, int length, byte[] dst) throws IOException {
        readFully(channel, address, position, length);
        MemoryBuffer.createUntrackedUnfreeableRawFrom(address, length).asByteBuffer().get(dst);
    }

    /** LRU cap on open region handles. */
    private void trimOpenRegions() {
        var it = openRegions.entrySet().iterator();
        while (openRegions.size() > MAX_OPEN_REGIONS && it.hasNext()) {
            var e = it.next();
            it.remove();
            e.getValue().free();
        }
    }

    @Override
    public void close() {
        for (var region : openRegions.values()) {
            region.free();
        }
        openRegions.clear();
    }
}
