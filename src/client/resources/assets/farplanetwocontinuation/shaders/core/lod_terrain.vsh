#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>

const float ROUGH_UNDER_DETAIL = 1.25;
// Beyond the ring the push fades out over this part of its radius. A step would break triangles crossing the ring edge into stripes;
// over this width the pushed depth still grows along every view ray, so nothing behind comes through.
const float ROUGH_UNDER_DETAIL_FADE = 0.5;
#ifdef WATER_PASS
// Detailed water comes a little closer for the depth test: where rough water lies under it, nearly level with it, it always wins.
const float WATER_PRIORITY = 0.998;
#endif

#ifdef TEXTURED
// Fixed point: x and z in 64ths of a block, y in 16ths; w is the sprite size in texels. UV0 is the sprite corner in half texels.
layout(location = 0) in ivec4 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in uvec2 UV0;
#else
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
#endif
#ifdef ROUGH
// Rough tiles: the second color (water, snow, tree tops, another biome), with the value telling which side of the border a point is
// on in alpha. The land color is Color; its alpha holds the void, the height of the tree tops and how soft the border is.
layout(location = 2) in vec4 WaterColor;
layout(location = 5) out vec4 waterColor;
// How soft the border between the two colors is drawn, in units of that value: 0 sharp.
layout(location = 6) out float edgeSoftness;
// The colors beyond the borders on land (the snow line, forest edges, biomes) and between waters, with the value of the border in
// alpha; beyond its border each takes the place of the land color or of the second one, see TileMeshBuilder#putVertex.
layout(location = 3) in vec4 LandThird;
layout(location = 4) in vec4 WaterThird;
layout(location = 7) out vec4 landThird;
layout(location = 8) out vec4 waterThird;
// Which side of the coast each corner is on: 1 water, -1 land. Interpolated, it is 1 or -1 only in a triangle with all corners on one
// side, which the coast does not cross.
layout(location = 9) out float coastSide;
#endif

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 relativePosition;
layout(location = 4) out vec4 viewPosition;
#ifdef TEXTURED
// Detailed tiles with block textures: the position inside the tile (its corner lies on the block grid), the sprite corner in the atlas
// and its size in texels, and how the face maps to the texture, kept in the alpha of the color: 255 minus the kind, minus 4 with gaps.
layout(location = 5) out vec3 tilePosition;
layout(location = 6) flat out vec4 sprite;
layout(location = 7) flat out int faceCode;
#endif

void main() {
#ifdef TEXTURED
    vec3 localPosition = vec3(Position.xyz) * vec3(1.0 / 64.0, 1.0 / 16.0, 1.0 / 64.0);
#else
    vec3 localPosition = Position;
#endif
    vec3 pos = localPosition + ModelOffset;
#ifndef TEXTURED
    // Rough tiles carry the height of tree tops in the alpha (see TileMeshBuilder#putVertex). Closer than where they
    // start to rise, the detailed terrain or vanilla's chunks are drawn, and tree tops would poke through them: there they lie flat.
#ifdef DEPTH_ONLY
    float canopyStart = ColorModulator.w < 0.0 ? -ColorModulator.w : 0.0;
#else
    float canopyStart = TextureMat[2][3];
#endif
#ifdef ROUGH
    // 0 is the void; land counts from 1: 21 steps per softness of its border, and within them the tree tops in steps of 1.5 blocks.
    // Each step of softness draws the border twice as wide as the one before.
    float code = floor(Color.a * 255.0 + 0.5);
    bool land = code > 0.5;
    float steps = code - 1.0;
    float softness = land ? floor(steps / 21.0) : 0.0;
    float canopy = land ? (steps - softness * 21.0) * 1.5 : 0.0;
    coastSide = WaterColor.a * 16.0 - 15.0 > 0.0 ? 1.0 : -1.0;
    edgeSoftness = softness > 0.5 ? 0.02 * exp2(softness) : 0.0;
#else
    bool land = Color.a > 0.5;
    float canopy = (1.0 - Color.a) * 255.0 / 4.0;
#endif
    if (canopyStart > 0.0 && land) {
        float rise = smoothstep(canopyStart, canopyStart + max(48.0, canopyStart * 0.3), length(pos.xz));
        pos.y -= canopy * (1.0 - rise);
    }
#endif
    viewPosition = ModelViewMat * vec4(pos, 1.0);
#ifdef DEPTH_ONLY
    gl_Position = ProjMat * viewPosition;
#else
    // Where two layers cover the same place, the more detailed one wins: less detailed tiles move away from the camera for the
    // depth test only (TextureMat[2][2] is slightly above 1 for them). Scaling along the view ray keeps every pixel where it is.
    // Within the ring of detailed tiles (TextureMat[2][1]) rough terrain is pushed much farther: its flat water at sea level would
    // otherwise cover the ground under detailed water, and only gaps in the detailed tiles are left for it to fill.
    float priority = TextureMat[2][2];
    if (TextureMat[2][1] > 0.0) {
        float outside = clamp((length(pos.xz) - TextureMat[2][1]) / (TextureMat[2][1] * ROUGH_UNDER_DETAIL_FADE), 0.0, 1.0);
        // Multiplied, so finer rough tiles keep winning over coarser ones here too.
        priority *= mix(ROUGH_UNDER_DETAIL, 1.0, outside);
    }
#ifdef WATER_PASS
    priority *= WATER_PRIORITY;
#endif
    gl_Position = ProjMat * vec4(viewPosition.xyz * priority, 1.0);
#endif

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
#ifdef DEPTH_ONLY
    // ColorModulator carries other parameters in this pass; only the alpha of the vertex is needed.
    vertexColor = Color;
#else
    vertexColor = Color * ColorModulator;
#endif
#ifdef ROUGH
    // The fragment shader only needs to know land from the void.
    vertexColor.a = land ? 1.0 : 0.0;
#endif
    relativePosition = pos;
#ifdef ROUGH
#ifdef DEPTH_ONLY
    waterColor = WaterColor;
    landThird = LandThird;
    waterThird = WaterThird;
#else
    waterColor = vec4(WaterColor.rgb * ColorModulator.rgb, WaterColor.a);
    landThird = vec4(LandThird.rgb * ColorModulator.rgb, LandThird.a);
    waterThird = vec4(WaterThird.rgb * ColorModulator.rgb, WaterThird.a);
#endif
#endif
#ifdef TEXTURED
    tilePosition = localPosition;
    sprite = vec4(vec2(UV0) * 0.5, float(Position.w), float(Position.w));
    faceCode = 255 - int(Color.a * 255.0 + 0.5);
#endif
}
