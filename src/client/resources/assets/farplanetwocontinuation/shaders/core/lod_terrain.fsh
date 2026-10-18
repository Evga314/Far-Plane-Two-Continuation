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

#if defined(TEXTURED) && !defined(DEPTH_ONLY)
uniform sampler2D Sampler0;

layout(location = 5) in vec3 tilePosition;
layout(location = 6) flat in vec4 sprite;
layout(location = 7) flat in int faceCode;

// The block texture at this point. The sprite is repeated over every block of the face; TextureMat[1][0] is the texture level to
// always use, below 0 the level is chosen by distance like vanilla does. Texels with gaps are dropped for faces marked so, and shown in
// the average color of the texture otherwise, like vanilla's fast leaves.
// blur from 0 to 1 makes the texture up to 16 times blurrier, for the fading edge of the detailed tiles.
vec4 blockTexture(float blur) {
    if (sprite.z <= 0.0) {
        return vec4(1.0);
    }
    int kind = faceCode & 3;
    vec2 coordinate = kind == 0 ? tilePosition.xz : kind == 2 ? vec2(tilePosition.z, -tilePosition.y) : vec2(tilePosition.x, -tilePosition.y);
    vec2 atlasSize = vec2(textureSize(Sampler0, 0));
    // The corner comes in texels; the atlas size turns it into texture coordinates.
    vec2 corner = sprite.xy / atlasSize;
    vec2 spriteSize = sprite.zw / atlasSize;
    vec2 halfTexel = 0.5 / atlasSize;
    vec2 uv = clamp(corner + fract(coordinate) * spriteSize, corner + halfTexel, corner + spriteSize - halfTexel);
    vec4 color;
    if (TextureMat[1][0] >= 0.0) {
        color = textureLod(Sampler0, uv, TextureMat[1][0]);
    } else {
        float widen = exp2(blur * 4.0);
        color = textureGrad(Sampler0, uv, dFdx(coordinate) * spriteSize * widen, dFdy(coordinate) * spriteSize * widen);
    }
    if (color.a < 0.5 && (faceCode & 8) == 0) {
        if ((faceCode & 4) != 0) {
            discard;
        }
        color = vec4(textureLod(Sampler0, corner + spriteSize * 0.5, 4.0).rgb * 0.8, 1.0);
    }
    return color;
}
#endif

#ifdef WATER_PASS
// The average color of the texture, from its smallest mipmap: the rough terrain paints water in it, see TileColors.
vec3 spriteAverage() {
    vec2 atlasSize = vec2(textureSize(Sampler0, 0));
    return textureLod(Sampler0, (sprite.xy + sprite.zw * 0.5) / atlasSize, 4.0).rgb;
}

// Where the water of detailed tiles starts to turn into rough water, as a share of the radius of the detailed tiles.
const float WATER_FADE_START = 0.7;
// Brightness of the rough terrain's water against the plain water color, see TileColors and TileMeshBuilder.WATER_SHADE.
const float ROUGH_WATER_SHADE = 0.85 * 0.92;
#endif

// Height of the vanilla sky disc above the camera. Everything closer than this angle to the horizon is drawn in the fog color by
// vanilla, so distant terrain fades into the same color over the same angle instead of ending in a hard line under a pale band.
const float SKY_DISC_HEIGHT = 16.0;

#ifdef DEPTH_ONLY
// ColorModulator: x = radius with the stronger push, y = 1 for zero-to-one depth, z = inner cut, w = outer cut. TextureMat = vanilla
// projection. Detailed tiles have blocks at exactly the same places as vanilla chunks. The depth is pushed slightly away from the camera,
// so the more detailed vanilla blocks always win instead of flickering against the same LOD blocks.
const float VANILLA_DEPTH_PUSH = 1.004;
// Rough terrain only guesses the surface. Within vanilla's render distance its depth is pushed much farther, so vanilla blocks near
// the guessed surface still win, while clouds and other things far behind LOD mountains stay hidden.
const float ROUGH_NEAR_DEPTH_PUSH = 1.03;
// ColorModulator.z below this marks a chunk vanilla draws partly: x holds the sections vanilla draws, one bit each from section
// CLIPPED_DEPTH - z up, w the camera height. Only the other sections write depth.
const float CLIPPED_DEPTH = -1000.0;

void main() {
    // Rough tiles mark the void between floating islands with zero alpha.
    if (vertexColor.a < 0.5) {
        discard;
    }
    float horizontal = length(relativePosition.xz);
    float push = VANILLA_DEPTH_PUSH;
    if (ColorModulator.z < CLIPPED_DEPTH * 0.5) {
        float worldY = relativePosition.y + ColorModulator.w;
        int section = int(floor((worldY - 0.002) / 16.0)) - int(round(CLIPPED_DEPTH - ColorModulator.z));
        int drawn = int(ColorModulator.x + 0.5);
        if (section >= 0 && section < 24 && ((drawn >> section) & 1) != 0) {
            discard;
        }
    } else {
        if (horizontal < ColorModulator.z || (ColorModulator.w > 0.0 && horizontal > ColorModulator.w)) {
            discard;
        }
        push = horizontal < ColorModulator.x ? ROUGH_NEAR_DEPTH_PUSH : VANILLA_DEPTH_PUSH;
    }
    vec4 clip = TextureMat * vec4(viewPosition.xyz * push, viewPosition.w);
    float ndc = clip.z / clip.w;
    gl_FragDepth = clamp(ColorModulator.y > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    fragColor = vec4(0.0);
}
#else
// TextureMat[0][0]: drop fragments closer than this (covered by detailed tiles); TextureMat[1][1]: drop fragments farther than this;
// TextureMat[1][3]: over this width inside the outer cut detailed tiles fade into the rough terrain under them: the opaque pass leaves
// the band out, the fade pass (FADE_PASS) blends it over the rough terrain and blurs its textures more and more towards the edge;
// TextureMat[3][3]: strength of the haze over the horizon, 0 in skies without the overworld sky disc; TextureMat[3].xyz is then the
// color distant terrain fades into, matching that sky instead of the fog color.
// TextureMat[0][3]: 1 drops the sections vanilla draws itself: TextureMat[1][2] holds them as one bit each, from section TextureMat[0][2]
// up; TextureMat[0][1] is the camera height, which turns positions back into world heights. A top face right on a section border
// belongs to the section below it.
void main() {
    // Rough tiles mark the void between floating islands with zero alpha; the island edge lies half way to the void sample.
    if (vertexColor.a < 0.5) {
        discard;
    }
    float horizontal = length(relativePosition.xz);
    if (horizontal < TextureMat[0][0] || (TextureMat[1][1] > 0.0 && horizontal > TextureMat[1][1])) {
        discard;
    }
    float fade = TextureMat[1][3] > 0.0 ? clamp((horizontal - TextureMat[1][1] + TextureMat[1][3]) / TextureMat[1][3], 0.0, 1.0) : 0.0;
#ifdef FADE_PASS
    if (fade <= 0.0) {
        discard;
    }
#elif !defined(WATER_PASS)
    if (fade > 0.0) {
        discard;
    }
#endif
    if (TextureMat[0][3] > 0.5) {
        float worldY = relativePosition.y + TextureMat[0][1];
        int section = int(floor((worldY - 0.002) / 16.0)) - int(TextureMat[0][2]);
        int drawn = int(TextureMat[1][2] + 0.5);
        if (section >= 0 && section < 24 && ((drawn >> section) & 1) != 0) {
            discard;
        }
    }
    vec4 fogColor = TextureMat[3][3] > 0.5 ? FogColor : vec4(TextureMat[3].xyz, FogColor.a);
    vec4 surface = vec4(vertexColor.rgb, 1.0);
#ifdef TEXTURED
    // Translucent water (marked with 8) is drawn in its own pass after everything else.
    bool water = (faceCode & 8) != 0;
#ifdef WATER_PASS
    if (!water) {
        discard;
    }
#else
    if (water) {
        discard;
    }
#endif
    vec4 texel = blockTexture(fade);
    surface = vec4(texel.rgb * vertexColor.rgb, 1.0);
#ifdef WATER_PASS
    surface.a = clamp(texel.a * 0.8, 0.45, 0.7);
    // Towards the edge of the detailed tiles (TextureMat[2][0]) the water turns into the flat, opaque water of the rough terrain
    // beyond it, so there is no second edge where the seabed suddenly stops showing.
    if (TextureMat[2][0] > 0.0) {
        float edge = smoothstep(TextureMat[2][0] * WATER_FADE_START, TextureMat[2][0], horizontal);
        surface = mix(surface, vec4(vertexColor.rgb * spriteAverage() * ROUGH_WATER_SHADE, 1.0), edge);
    }
    // The fading edge of the detailed tiles takes their water with it.
    surface.a *= 1.0 - fade;
#endif
#endif
    vec4 color = apply_fog(surface, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);

    float horizonSine = abs(relativePosition.y) / max(sphericalVertexDistance, 1.0);
    float bandSine = SKY_DISC_HEIGHT / max(FogSkyEnd, SKY_DISC_HEIGHT);
    float haze = 1.0 - smoothstep(0.0, bandSine, horizonSine);
    haze *= smoothstep(1024.0, 8192.0, cylindricalVertexDistance) * TextureMat[3][3];
#ifdef FADE_PASS
    surface.a = 1.0 - fade;
#endif
    fragColor = vec4(mix(color.rgb, FogColor.rgb, haze * FogColor.a), surface.a);
}
#endif
