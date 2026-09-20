package me.cortex.voxy.commonImpl.importers;

import com.mojang.serialization.Codec;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.UnsafeUtil;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import me.cortex.voxy.common.voxelization.WorldVoxilizedSectionMipper;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldUpdater;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.lwjgl.system.MemoryUtil;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Converts saved chunk NBT into voxel sections and ingests them into a WorldEngine.
 *  Shared by whole-world imports and the incremental background terrain filling.
 *  Thread safe for concurrent calls from distinct threads (section scratch is
 *  per-thread, ingest goes through the world updater). */
public class ChunkNBTIngestor {
    private final WorldEngine world;
    private final PalettedContainerRO<Holder<Biome>> defaultBiomeProvider;
    private final Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec;
    private final Codec<PalettedContainer<BlockState>> blockStateCodec;

    public ChunkNBTIngestor(WorldEngine worldEngine, Level mcWorld) {
        this.world = worldEngine;

        var biomeRegistry = mcWorld.registryAccess().registryOrThrow(Registries.BIOME);
        var defaultBiome = biomeRegistry.getHolder(Biomes.PLAINS).orElseThrow();
        this.defaultBiomeProvider = new PalettedContainerRO<>() {
            @Override
            public Holder<Biome> get(int x, int y, int z) {
                return defaultBiome;
            }

            @Override
            public void getAll(java.util.function.Consumer<Holder<Biome>> action) {

            }

            @Override
            public void write(FriendlyByteBuf buf) {

            }

            @Override
            public int getSerializedSize() {
                return 0;
            }

            @Override
            public boolean maybeHas(java.util.function.Predicate<Holder<Biome>> predicate) {
                return false;
            }

            @Override
            public void count(PalettedContainer.CountConsumer<Holder<Biome>> counter) {

            }

            @Override
            public PalettedContainer<Holder<Biome>> recreate() {
                return null;
            }

            @Override
            public PalettedContainerRO.PackedData<Holder<Biome>> pack(IdMap<Holder<Biome>> idMap, PalettedContainer.Strategy strategy) {
                return null;
            }
        };

        this.biomeCodec = PalettedContainer.codecRO(biomeRegistry.asHolderIdMap(), biomeRegistry.holderByNameCodec(), PalettedContainer.Strategy.SECTION_BIOMES, biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
        this.blockStateCodec = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    }

    /** Ingests one saved chunk (full NBT as stored in region files). Returns false
     *  when the chunk is not a real full chunk (not yet generated, etc). */
    public boolean ingestChunkNBT(CompoundTag chunk, int regionX, int regionZ) {
        if (!chunk.contains("Status")) {
            return false;
        }

        //Dont process non full chunk sections
        var status = ChunkStatus.byName(chunk.getString("Status"));
        if (status != ChunkStatus.FULL && status != ChunkStatus.EMPTY) {//We also import empty since they are from data upgrade
            return false;
        }

        try {
            int x = chunk.getInt("xPos");
            int z = chunk.getInt("zPos");
            if (x>>5 != regionX || z>>5 != regionZ) {
                Logger.error("Chunk position is not located in correct region, expected: (" + regionX + ", " + regionZ+"), got: " + "(" + (x>>5) + ", " + (z>>5)+"), importing anyway");
            }

            for (var sectionE : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                var section = (CompoundTag) sectionE;
                int y = section.getInt("Y");
                this.importSectionNBT(x, y, z, section);
            }
        } catch (Exception e) {
            Logger.error("Exception importing world chunk:",e);
        }
        return true;
    }

    public static InputStream createInputStream(MemoryBuffer data) {
        return new InputStream() {
            private long offset = 0;
            @Override
            public int read() {
                return MemoryUtil.memGetByte(data.address + (this.offset++)) & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                len = Math.min(len, this.available());
                if (len == 0) {
                    return -1;
                }
                UnsafeUtil.memcpy(data.address+this.offset, len, b, off); this.offset+=len;
                return len;
            }

            @Override
            public int available() {
                return (int) (data.size-this.offset);
            }
        };
    }

    public static DataInputStream decompress(byte flags, MemoryBuffer stream) throws IOException {
        RegionFileVersion chunkStreamVersion = RegionFileVersion.fromId(flags);
        if (chunkStreamVersion == null) {
            Logger.error("Chunk has invalid chunk stream version");
            return null;
        } else {
            return new DataInputStream(chunkStreamVersion.wrap(createInputStream(stream)));
        }
    }

    private static final byte[] EMPTY = new byte[0];
    private static final ThreadLocal<VoxelizedSection> SECTION_CACHE = ThreadLocal.withInitial(VoxelizedSection::createEmpty);
    private void importSectionNBT(int x, int y, int z, CompoundTag section) {
        if (section.getCompound("block_states").isEmpty()) {
            return;
        }

        byte[] blockLightData = section.getByteArray("BlockLight");
        byte[] skyLightData = section.getByteArray("SkyLight");

        DataLayer blockLight;
        if (blockLightData.length != 0) {
            blockLight = new DataLayer(blockLightData);
        } else {
            blockLight = null;
        }

        DataLayer skyLight;
        if (skyLightData.length != 0) {
            skyLight = new DataLayer(skyLightData);
        } else {
            skyLight = null;
        }

        var blockStatesRes = blockStateCodec.parse(NbtOps.INSTANCE, section.getCompound("block_states"));
        //? if 1.20.1 {
        blockStatesRes.get().ifRight(partial -> {
            return;
        });
        //?} else {
        if (!blockStatesRes.hasResultOrPartial()) {
            //TODO: if its only partial, it means should try to upgrade the nbt format with datafixerupper probably
            return;
        }
        //?}
        var blockStates = blockStatesRes.getPartialOrThrow();
        var biomes = this.defaultBiomeProvider;
        var optBiomes = section.getCompound("biomes");
        if (!optBiomes.isEmpty()) {
            biomes = this.biomeCodec.parse(NbtOps.INSTANCE, optBiomes).result().orElse(this.defaultBiomeProvider);
        }
        VoxelizedSection csec = WorldConversionFactory.convert(
                SECTION_CACHE.get().setPosition(x, y, z),
                this.world.getMapper(),
                blockStates,
                biomes,
                (bx, by, bz) -> {
                    int block = 0;
                    int sky = 0;
                    if (blockLight != null) {
                        block = blockLight.get(bx, by, bz);
                    }
                    if (skyLight != null) {
                        sky = skyLight.get(bx, by, bz);
                    }
                    return (byte) (sky|(block<<4));
                }
        );

        WorldVoxilizedSectionMipper.mipSection(csec, this.world.getMapper());
        WorldUpdater.insertUpdate(this.world, csec);
    }
}
