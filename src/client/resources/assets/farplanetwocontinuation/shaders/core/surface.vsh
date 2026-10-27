#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <farplanetwocontinuation:surface.glsl>

// Vanilla's lightmap: rough terrain lies in the open, in the full light of the sky and no block light.
uniform sampler2D Sampler2;

// See LodVertex: x, z, y and the level of the water; the normal, the tree tops, the material and flags; the color; the color beyond
// the border with its value.
layout(location = 0) in vec4 Position;
layout(location = 1) in vec4 Extra;
layout(location = 2) in vec4 Color;
layout(location = 3) in vec4 Second;

layout(location = 0) out vec3 relativePosition;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) out vec4 secondColor;
layout(location = 3) out float softness;
layout(location = 4) out float waterLevel;
#ifdef DEPTH
layout(location = 5) out vec4 vanillaClip;
#endif

const int BIT_WATER = 16;

#ifdef RING
// The ground under vanilla's edge (see OwnershipMap and FrameDrawList.ringInfo): per chunk column the sides the rough terrain owns
// and the floor under vanilla's blocks.
uniform isamplerBuffer RingInfo;

// Over this many blocks in from the rough terrain's own columns the ring goes down to vanilla's floor: at their border it runs on
// from them as it is, deeper inside it can never rise over vanilla's blocks, where it would show against the sky.
const float RING_RAMP = 8.0;

float ringGround(vec3 pos) {
    int around = texelFetch(RingInfo, 2).r;
    if (around < 0) {
        return pos.y;
    }
    vec2 world = pos.xz + vec2(intBitsToFloat(texelFetch(RingInfo, 0).r), intBitsToFloat(texelFetch(RingInfo, 1).r));
    vec2 chunkCorner = floor(world / 16.0);
    vec2 local = world - chunkCorner * 16.0;
    ivec2 chunk = ivec2(chunkCorner) + around;
    int side = 2 * around + 1;
    if (chunk.x < 0 || chunk.y < 0 || chunk.x >= side || chunk.y >= side) {
        return pos.y;
    }
    int value = texelFetch(RingInfo, 4 + chunk.x + chunk.y * side).r;
    int floorCode = (value >> 16) & 0xFFFF;
    if (floorCode == 0) {
        return pos.y;
    }
    float floorY = float(floorCode) - intBitsToFloat(texelFetch(RingInfo, 3).r);
    // How far in the point is from the nearest side or corner the rough terrain owns; a column with none (being handed over to
    // vanilla) goes all the way down.
    float inside = 16.0;
    vec2 far = 16.0 - local;
    if ((value & 1) != 0) inside = min(inside, local.x);
    if ((value & 2) != 0) inside = min(inside, far.x);
    if ((value & 4) != 0) inside = min(inside, local.y);
    if ((value & 8) != 0) inside = min(inside, far.y);
    if ((value & 16) != 0) inside = min(inside, max(local.x, local.y));
    if ((value & 32) != 0) inside = min(inside, max(far.x, local.y));
    if ((value & 64) != 0) inside = min(inside, max(local.x, far.y));
    if ((value & 128) != 0) inside = min(inside, max(far.x, far.y));
    return mix(pos.y, min(pos.y, floorY), smoothstep(0.0, RING_RAMP, inside));
}
#endif

void main() {
    // Normalized on the way in (see OwnPassOutput.FORMAT): back to the whole numbers of LodVertex.
    vec4 units = floor(Position * 65535.0 + 0.5);
    ivec4 extra = ivec4(floor(Extra * 255.0 + 0.5));
    vec3 pos = vec3(units.x * TileOffset.w, units.z / 16.0, units.y * TileOffset.w) + TileOffset.xyz;
    waterLevel = units.w / 16.0 + TileOffset.y;
    bool water = (extra.w & BIT_WATER) != 0;
    // Closer than where tree tops start to rise, vanilla's chunks and the detailed tiles are drawn, and the tree tops would poke
    // through them: there they lie flat.
    if (Params.x > 0.0 && !water) {
        float rise = smoothstep(Params.x, Params.x + max(48.0, Params.x * 0.3), length(pos.xz));
        pos.y -= float(extra.z) * 0.25 * (1.0 - rise);
    }
#ifdef RING
    pos.y = ringGround(pos);
#endif
    vec4 view = ViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * vec4(view.xyz * TileParams.x, 1.0);
#ifdef BACKGROUND
    // At the far plane (the depth is reversed): only where vanilla drew nothing does it pass the depth test, see VanillaPassOutput.
    gl_Position.z = 0.0;
#endif
#ifdef DEPTH
    vanillaClip = VanillaProjMat * vec4(view.xyz * Params.w, view.w);
#endif
    relativePosition = pos;
    vec3 light = Light.rgb * sample_lightmap(Sampler2, ivec2(0, 240)).rgb;
    // The ground keeps the softness of its border in the alpha, 0 sharp, each step twice as wide; the water its opacity.
    float steps = floor(Color.a * 255.0 + 0.5);
    softness = !water && steps > 0.5 ? 0.02 * exp2(steps) : 0.0;
    vertexColor = vec4(Color.rgb * light, water ? Color.a : 1.0);
    secondColor = vec4(Second.rgb * light, Second.a);
}
