package com.Evga314.farplanetwocontinuation.mixin.common;

import com.Evga314.farplanetwocontinuation.server.DirtyChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes chunks where a block was replaced by another one on the server, so the detailed LOD tiles over them are built again. Changes
 * of the same block (crops growing, redstone switching, water flowing) and blocks world generation places while it finishes a fresh
 * chunk are skipped, they would only cause needless reloading.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    private Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void farplane$noteChange(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        // World generation finishing a fresh chunk places its blocks with a known shape; those are not player changes.
        if (old == null || (flags & Block.UPDATE_KNOWN_SHAPE) != 0 || !(this.level instanceof ServerLevel)) {
            return;
        }
        if (old.getBlock() != state.getBlock()) {
            DirtyChunks.mark(this.level.dimension(), pos.getX() >> 4, pos.getZ() >> 4);
        }
    }
}
