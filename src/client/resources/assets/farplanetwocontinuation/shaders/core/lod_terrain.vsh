#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>

const float ROUGH_UNDER_DETAIL = 1.25;
// Beyond the ring the push fades out over this part of its radius. A step would break triangles crossing the ring edge into stripes;
// over this width the pushed depth still grows along every view ray, so nothing behind comes through.
const float ROUGH_UNDER_DETAIL_FADE = 0.5;

#ifdef TEXTURED
// Fixed point: x and z in 64ths of a block, y in 16ths; w is the sprite size in texels. UV0 is the sprite corner in half texels.
layout(location = 0) in ivec4 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in uvec2 UV0;
#else
layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
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
    // Rough tiles carry the height of tree tops in the alpha (255 minus quarter blocks, see TileMeshBuilder). Closer than where they
    // start to rise, the detailed terrain or vanilla's chunks are drawn, and tree tops would poke through them: there they lie flat.
#ifdef DEPTH_ONLY
    float canopyStart = ColorModulator.w < 0.0 ? -ColorModulator.w : 0.0;
#else
    float canopyStart = TextureMat[2][3];
#endif
    if (canopyStart > 0.0 && Color.a > 0.5) {
        float canopy = (1.0 - Color.a) * 255.0 / 4.0;
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
        priority = max(priority, mix(ROUGH_UNDER_DETAIL, 1.0, outside));
    }
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
    relativePosition = pos;
#ifdef TEXTURED
    tilePosition = localPosition;
    sprite = vec4(vec2(UV0) * 0.5, float(Position.w), float(Position.w));
    faceCode = 255 - int(Color.a * 255.0 + 0.5);
#endif
}
