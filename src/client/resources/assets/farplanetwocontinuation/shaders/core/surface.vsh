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

void main() {
    // Normalized on the way in (see OwnPassOutput.FORMAT): back to the whole numbers of LodVertex.
    vec4 units = floor(Position * 65535.0 + 0.5);
    ivec4 extra = ivec4(floor(Extra * 255.0 + 0.5));
    vec3 pos = vec3(units.x * TileOffset.w, units.z / 16.0, units.y * TileOffset.w) + TileOffset.xyz;
    waterLevel = units.w / 16.0 + TileOffset.y;
    bool water = (extra.w & BIT_WATER) != 0;
    vec4 view = ViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * vec4(view.xyz * TileParams.x, 1.0);
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
