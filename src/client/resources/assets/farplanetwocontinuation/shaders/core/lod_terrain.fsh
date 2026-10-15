#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 relativePosition;
layout(location = 4) in vec4 viewPosition;

layout(location = 0) out vec4 fragColor;

// Height of the vanilla sky disc above the camera. Everything closer than this angle to the horizon is drawn in the fog color by
// vanilla, so distant terrain fades into the same color over the same angle instead of ending in a hard line under a pale band.
const float SKY_DISC_HEIGHT = 16.0;

#ifdef DEPTH_ONLY
// ColorModulator: x = vanilla render distance, y = 1 for zero-to-one depth, z = inner cut, w = outer cut. TextureMat = vanilla projection.
// Detailed tiles have blocks at exactly the same places as vanilla chunks. The depth is pushed slightly away from the camera, so the
// more detailed vanilla blocks always win instead of flickering against the same LOD blocks.
const float VANILLA_DEPTH_PUSH = 1.004;

void main() {
    float horizontal = length(relativePosition.xz);
    if (horizontal < ColorModulator.x || horizontal < ColorModulator.z || (ColorModulator.w > 0.0 && horizontal > ColorModulator.w)) {
        discard;
    }
    vec4 clip = TextureMat * vec4(viewPosition.xyz * VANILLA_DEPTH_PUSH, viewPosition.w);
    float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(ColorModulator.y > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    fragColor = vec4(0.0);
}
#else
// TextureMat[0][0]: drop fragments closer than this (covered by detailed tiles); TextureMat[1][1]: drop fragments farther than this.
void main() {
    float horizontal = length(relativePosition.xz);
    if (horizontal < TextureMat[0][0] || (TextureMat[1][1] > 0.0 && horizontal > TextureMat[1][1])) {
        discard;
    }
    vec4 color = apply_fog(vertexColor, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

    float horizonSine = abs(relativePosition.y) / max(sphericalVertexDistance, 1.0);
    float bandSine = SKY_DISC_HEIGHT / max(FogSkyEnd, SKY_DISC_HEIGHT);
    float haze = 1.0 - smoothstep(0.0, bandSine, horizonSine);
    haze *= smoothstep(1024.0, 8192.0, cylindricalVertexDistance);
    fragColor = vec4(mix(color.rgb, FogColor.rgb, haze * FogColor.a), color.a);
}
#endif
