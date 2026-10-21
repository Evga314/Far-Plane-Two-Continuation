package com.Evga314.farplanetwocontinuation.mixin.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Structure templates are shared by every thread that places structures, and each palette fills a plain hash map of its blocks by
 * type on first use. The virtual terrain works out structures on its own threads next to vanilla's world generation, so two threads
 * can fill it at once, which vanilla answers with a crash. A concurrent map lets them.
 */
@Mixin(StructureTemplate.Palette.class)
public abstract class StructurePaletteMixin {
    @Shadow
    @Final
    @Mutable
    private Map<Block, List<StructureTemplate.StructureBlockInfo>> cache;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void farplane$concurrentCache(CallbackInfo ci) {
        this.cache = new ConcurrentHashMap<>();
    }
}
