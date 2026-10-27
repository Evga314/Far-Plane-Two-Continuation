package com.Evga314.farplanetwocontinuation.mixin.iris;

import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.gl.program.ProgramImages;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The parts of a DH program the LOD terrain updates itself before drawing: Iris's own update of them asks DH for its planes (see
 * IrisOutput).
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public interface IrisLodRenderProgramAccessor {
    @Accessor("uniforms")
    ProgramUniforms farplane$uniforms();

    @Accessor("samplers")
    ProgramSamplers farplane$samplers();

    @Accessor("images")
    ProgramImages farplane$images();

    @Accessor("customUniforms")
    CustomUniforms farplane$customUniforms();
}