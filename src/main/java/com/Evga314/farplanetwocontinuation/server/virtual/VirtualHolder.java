package com.Evga314.farplanetwocontinuation.server.virtual;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;

/** Hands a chunk that only exists in memory to a {@link VirtualRegion}, whatever status the region asks for. */
final class VirtualHolder extends GenerationChunkHolder {
    private final ChunkAccess chunk;

    VirtualHolder(ChunkPos pos, ChunkAccess chunk) {
        super(pos);
        this.chunk = chunk;
    }

    @Override
    public @Nullable ChunkAccess getChunkIfPresentUnchecked(ChunkStatus status) {
        return this.chunk;
    }

    @Override
    protected void addSaveDependency(CompletableFuture<?> sync) {
    }

    @Override
    public int getTicketLevel() {
        return 0;
    }

    @Override
    public int getQueueLevel() {
        return 0;
    }
}
