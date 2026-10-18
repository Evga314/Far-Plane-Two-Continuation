package com.Evga314.farplanetwocontinuation.net;

import com.Evga314.farplanetwocontinuation.FarPlaneConstants;
import com.Evga314.farplanetwocontinuation.tile.DetailData;
import com.Evga314.farplanetwocontinuation.tile.TileData;
import io.netty.handler.codec.DecoderException;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class FarPlanePayloads {
    /** Upper bound for tiles in one request packet, checked while decoding so a client cannot make the server allocate much. */
    public static final int MAX_REQUESTS_PER_PACKET = 64;
    /**
     * Added to the level of a requested rough tile when nothing is drawn where it goes yet. The server answers such tiles before tiles
     * that only refine terrain already on screen, so the view is filled first.
     */
    public static final int REQUEST_HOLE = 0x40;
    public static final int MAX_DIRTY_CHUNKS = 2048;

    private FarPlanePayloads() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(FarPlaneConstants.MOD_ID, path);
    }

    /**
     * Sent by the server when a player joins or changes dimension. Without it the client keeps LOD rendering disabled.
     */
    public record Hello(int protocol, boolean enabled, int maxDistance, int minLevel, boolean detailEnabled, int detailMaxRadiusChunks)
        implements CustomPacketPayload {
        public static final Type<Hello> TYPE = new Type<>(id("hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(Hello::write, Hello::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.protocol);
            buf.writeBoolean(this.enabled);
            buf.writeVarInt(this.maxDistance);
            buf.writeVarInt(this.minLevel);
            buf.writeBoolean(this.detailEnabled);
            buf.writeVarInt(this.detailMaxRadiusChunks);
        }

        private static Hello read(FriendlyByteBuf buf) {
            int protocol = buf.readVarInt();
            boolean enabled = buf.readBoolean();
            int maxDistance = buf.readVarInt();
            int minLevel = buf.readVarInt();
            // Older servers end here; they only know the rough terrain.
            boolean detailEnabled = buf.isReadable() && buf.readBoolean();
            int detailRadius = buf.isReadable() ? buf.readVarInt() : 0;
            return new Hello(protocol, enabled, maxDistance, minLevel, detailEnabled, detailRadius);
        }

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /**
     * Client asks for a batch of tiles in the dimension it is currently in. {@code requests} holds (level, x, z) triples.
     */
    public record TileRequest(int[] requests) implements CustomPacketPayload {
        public static final Type<TileRequest> TYPE = new Type<>(id("tile_request"));
        public static final StreamCodec<FriendlyByteBuf, TileRequest> CODEC = CustomPacketPayload.codec(TileRequest::write, TileRequest::read);

        private void write(FriendlyByteBuf buf) {
            int count = this.requests.length / 3;
            buf.writeVarInt(count);
            for (int i = 0; i < count * 3; i++) {
                buf.writeVarInt(this.requests[i]);
            }
        }

        private static TileRequest read(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_REQUESTS_PER_PACKET) {
                throw new DecoderException("Too many tile requests: " + count);
            }
            int[] requests = new int[count * 3];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = buf.readVarInt();
            }
            return new TileRequest(requests);
        }

        @Override
        public Type<TileRequest> type() {
            return TYPE;
        }
    }

    /**
     * One generated tile. The body is the {@link TileData#encode()} blob and is validated again on the client.
     */
    public record TileDataPayload(Identifier dimension, int level, int x, int z, byte[] data) implements CustomPacketPayload {
        public static final Type<TileDataPayload> TYPE = new Type<>(id("tile_data"));
        public static final StreamCodec<FriendlyByteBuf, TileDataPayload> CODEC = CustomPacketPayload.codec(TileDataPayload::write, TileDataPayload::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeIdentifier(this.dimension);
            buf.writeVarInt(this.level);
            buf.writeVarInt(this.x);
            buf.writeVarInt(this.z);
            buf.writeByteArray(this.data);
        }

        private static TileDataPayload read(FriendlyByteBuf buf) {
            return new TileDataPayload(buf.readIdentifier(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(TileData.MAX_ENCODED_SIZE));
        }

        @Override
        public Type<TileDataPayload> type() {
            return TYPE;
        }
    }

    /** Same as {@link TileRequest}, for detailed tiles built from real chunks. */
    /** Detailed tiles to make; {@code generate} asks for chunks nobody has visited yet to be generated in memory. */
    /** {@code urgency}: 0-100, how much loading speed matters to the player now, see {@code WorkThrottle}. */
    public record DetailRequest(int[] requests, boolean generate, int urgency) implements CustomPacketPayload {
        public static final Type<DetailRequest> TYPE = new Type<>(id("detail_request"));
        public static final StreamCodec<FriendlyByteBuf, DetailRequest> CODEC = CustomPacketPayload.codec(DetailRequest::write, DetailRequest::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.generate);
            buf.writeByte(Math.clamp(this.urgency, 0, 100));
            int count = this.requests.length / 3;
            buf.writeVarInt(count);
            for (int i = 0; i < count * 3; i++) {
                buf.writeVarInt(this.requests[i]);
            }
        }

        private static DetailRequest read(FriendlyByteBuf buf) {
            boolean generate = buf.readBoolean();
            int urgency = Math.clamp(buf.readUnsignedByte(), 0, 100);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_REQUESTS_PER_PACKET) {
                throw new DecoderException("Too many detail requests: " + count);
            }
            int[] requests = new int[count * 3];
            for (int i = 0; i < requests.length; i++) {
                requests[i] = buf.readVarInt();
            }
            return new DetailRequest(requests, generate, urgency);
        }

        @Override
        public Type<DetailRequest> type() {
            return TYPE;
        }
    }

    /** One detailed tile; the body is the compressed {@link DetailData#encode()} blob. */
    public record DetailDataPayload(Identifier dimension, int level, int x, int z, byte[] data) implements CustomPacketPayload {
        public static final Type<DetailDataPayload> TYPE = new Type<>(id("detail_data"));
        public static final StreamCodec<FriendlyByteBuf, DetailDataPayload> CODEC = CustomPacketPayload.codec(DetailDataPayload::write, DetailDataPayload::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeIdentifier(this.dimension);
            buf.writeVarInt(this.level);
            buf.writeVarInt(this.x);
            buf.writeVarInt(this.z);
            buf.writeByteArray(this.data);
        }

        private static DetailDataPayload read(FriendlyByteBuf buf) {
            return new DetailDataPayload(buf.readIdentifier(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(DetailData.MAX_COMPRESSED_SIZE));
        }

        @Override
        public Type<DetailDataPayload> type() {
            return TYPE;
        }
    }

    /**
     * Client asks the server to forget every cached tile. Only the singleplayer host and server admins are allowed to do that.
     */
    public record ClearCache() implements CustomPacketPayload {
        public static final ClearCache INSTANCE = new ClearCache();
        public static final Type<ClearCache> TYPE = new Type<>(id("clear_cache"));
        public static final StreamCodec<FriendlyByteBuf, ClearCache> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<ClearCache> type() {
            return TYPE;
        }
    }

    /** Answer to {@link ClearCache}: whether the server cache was actually cleared. */
    /** Chunks where blocks changed; the client loads the detailed tiles over them again. */
    public record DetailDirty(Identifier dimension, long[] chunks) implements CustomPacketPayload {
        public static final Type<DetailDirty> TYPE = new Type<>(id("detail_dirty"));
        public static final StreamCodec<FriendlyByteBuf, DetailDirty> CODEC = CustomPacketPayload.codec(DetailDirty::write, DetailDirty::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeIdentifier(this.dimension);
            buf.writeVarInt(this.chunks.length);
            for (long chunk : this.chunks) {
                buf.writeLong(chunk);
            }
        }

        private static DetailDirty read(FriendlyByteBuf buf) {
            Identifier dimension = buf.readIdentifier();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_DIRTY_CHUNKS) {
                throw new DecoderException("Too many changed chunks: " + count);
            }
            long[] chunks = new long[count];
            for (int i = 0; i < count; i++) {
                chunks[i] = buf.readLong();
            }
            return new DetailDirty(dimension, chunks);
        }

        @Override
        public Type<DetailDirty> type() {
            return TYPE;
        }
    }

    public record CacheCleared(boolean cleared) implements CustomPacketPayload {
        public static final Type<CacheCleared> TYPE = new Type<>(id("cache_cleared"));
        public static final StreamCodec<FriendlyByteBuf, CacheCleared> CODEC = CustomPacketPayload.codec(CacheCleared::write, CacheCleared::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeBoolean(this.cleared);
        }

        private static CacheCleared read(FriendlyByteBuf buf) {
            return new CacheCleared(buf.readBoolean());
        }

        @Override
        public Type<CacheCleared> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(Hello.TYPE, Hello.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TileDataPayload.TYPE, TileDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CacheCleared.TYPE, CacheCleared.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DetailDataPayload.TYPE, DetailDataPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(DetailDirty.TYPE, DetailDirty.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(DetailRequest.TYPE, DetailRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TileRequest.TYPE, TileRequest.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClearCache.TYPE, ClearCache.CODEC);
    }
}
