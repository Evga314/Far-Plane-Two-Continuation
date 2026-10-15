package com.Evga314.farplanetwocontinuation.server.virtual;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * A world generation region over chunks that only exist in memory. It behaves like vanilla's region, except that placing blocks never
 * reaches the real world: vanilla also registers points of interest (beds, bells, workstations) with the level, which would leave
 * phantom village blocks in the save for chunks that were never generated.
 */
final class VirtualRegion extends WorldGenRegion {
    VirtualRegion(ServerLevel level, StaticCache2D<GenerationChunkHolder> cache, ChunkStep step, ChunkAccess center) {
        super(level, cache, step, center);
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState blockState, int updateFlags, int updateLimit) {
        if (!this.ensureCanWrite(pos)) {
            return false;
        }
        ChunkAccess chunk = this.getChunk(pos);
        BlockState oldState = chunk.setBlockState(pos, blockState, updateFlags);
        if (blockState.hasBlockEntity()) {
            // Only the blocks are looked at, so a placeholder like the one vanilla uses for proto chunks is enough.
            CompoundTag tag = new CompoundTag();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putString("id", "DUMMY");
            chunk.setBlockEntityNbt(tag);
        } else if (oldState != null && oldState.hasBlockEntity()) {
            chunk.removeBlockEntity(pos);
        }
        return true;
    }
}
