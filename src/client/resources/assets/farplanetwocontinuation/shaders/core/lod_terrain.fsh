#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

// Tiles with nothing cut away are drawn with NO_DISCARD: a shader that never drops a fragment lets the GPU reject hidden fragments
// before shading them (on Adreno, its low resolution depth test only works for such draws).
#ifdef NO_DISCARD
#define DROP
#else
#define DROP discard
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec3 relativePosition;
layout(location = 4) in vec4 viewPosition;

layout(location = 0) out vec4 fragColor;
#ifdef ROUGH
layout(location = 5) in vec4 waterColor;
layout(location = 6) in float edgeSoftness;
layout(location = 7) in vec4 landThird;
layout(location = 8) in vec4 waterThird;
layout(location = 9) in float coastSide;
#endif

#if defined(ROUGH) && !defined(NO_DISCARD)
// The chunks around the camera vanilla draws: how far the camera is from the corner of the chunk in the middle (x and z, float bits),
// how many chunks it reaches from the middle (below 0 none), how far vanilla draws above and below the camera (float bits), the height
// of the camera (float bits, see the vertex shader), then per chunk 1 where vanilla draws it, 2 where it does next to chunks it does
// not draw, 256 where detailed tiles do, and 512 more along their edge, and from bit 16 the floor under their blocks. See
// LodManager.ChunkMask.
uniform isamplerBuffer ChunkMask;

// Whose this fragment of the rough terrain is: 0 its own; 1 vanilla's, in chunks vanilla draws, as far above and below the camera as it
// draws sections (lower down only the rough terrain fills the view), 2 the same along the edge of what vanilla draws; 3 the detailed
// tiles', in their chunks, but 1 along their edge, where the water is still drawn under theirs.
int leftToOthers(vec3 position) {
    int chunks = texelFetch(ChunkMask, 2).r;
    if (chunks < 0) {
        return 0;
    }
    vec2 camera = vec2(intBitsToFloat(texelFetch(ChunkMask, 0).r), intBitsToFloat(texelFetch(ChunkMask, 1).r));
    ivec2 chunk = ivec2(floor((position.xz + camera) / 16.0)) + chunks;
    int side = 2 * chunks + 1;
    if (chunk.x < 0 || chunk.y < 0 || chunk.x >= side || chunk.y >= side) {
        return 0;
    }
    int value = texelFetch(ChunkMask, 5 + chunk.x + chunk.y * side).r;
    if ((value & 256) != 0) {
        return (value & 512) != 0 ? 1 : 3;
    }
    return (value & 3) != 0 && abs(position.y) < intBitsToFloat(texelFetch(ChunkMask, 3).r) ? value & 3 : 0;
}
#endif

#if defined(TEXTURED) && defined(DEPTH_ONLY)
// How the face maps to the texture and whether it is translucent water, see the vertex shader.
layout(location = 7) flat in int faceCode;
#endif

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
            DROP;
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

// Under water with its fog off a wall in the color of that fog closes the view at the edge of the detailed tiles: TextureMat[3][0] is its
// radius (0 without), [3][1] the water surface, [3][2] how much darker it gets with the camera deeper down, [0][1] the camera height.
// Lower down it gets darker like the sea, and the terrain fades into it from WALL_FADE_START of the radius on. See LodRenderer.
const float WALL_FADE_START = 0.7;
const float WALL_DEEPEST = 0.2;
const float WALL_DARKENING_DEPTH = 20.0;
vec3 wallColor(float worldY) {
    float depth = max(TextureMat[3][1] - worldY, 0.0);
    return FogColor.rgb * TextureMat[3][2] * mix(WALL_DEEPEST, 1.0, exp(-depth / WALL_DARKENING_DEPTH));
}

#ifdef DEPTH_ONLY
// ColorModulator: x = radius within which no depth is written, y = 1 for zero-to-one depth, z = inner cut, w = outer cut. TextureMat = vanilla
// projection. Detailed tiles have blocks at exactly the same places as vanilla chunks. The depth is pushed slightly away from the camera,
// so the more detailed vanilla blocks always win instead of flickering against the same LOD blocks.
const float VANILLA_DEPTH_PUSH = 1.004;
// ColorModulator.z below this marks a chunk vanilla draws partly: x holds the sections vanilla draws, one bit each from section
// CLIPPED_DEPTH - z up, w the camera height. Only the other sections write depth.
const float CLIPPED_DEPTH = -1000.0;

void main() {
    // Rough tiles mark the void between floating islands with zero alpha.
    if (vertexColor.a < 0.5) {
        discard;
    }
#ifdef FLOATING
    // The same cut as the color pass, so the depth ends at the island edge too.
    if (waterColor.a * 16.0 - 15.0 < 0.0) {
        discard;
    }
#endif
#ifdef ROUGH
    // Where vanilla or the detailed tiles draw, the rough terrain writes no depth, so whatever they draw is never hidden behind it.
    if (leftToOthers(relativePosition) != 0) {
        discard;
    }
#endif
#ifdef TEXTURED
    // Translucent water writes no depth for vanilla: in a chunk vanilla draws partly, its blocks under the water would be hidden.
    if ((faceCode & 8) != 0) {
        discard;
    }
#endif
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
        // Rough terrain only guesses the surface: within vanilla's render distance it writes no depth at all, as any push would
        // still hide vanilla blocks near the guessed surface somewhere (kelp under its water, for one), flickering as the camera moves.
        if (horizontal < ColorModulator.z || (ColorModulator.w > 0.0 && horizontal > ColorModulator.w) || horizontal < ColorModulator.x) {
            discard;
        }
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
// TextureMat[3][3]: 1 under the overworld sky, 0 in skies without its sky disc; TextureMat[3].xyz is then the
// color distant terrain fades into, matching that sky instead of the fog color.
// TextureMat[0][3]: 1 drops the sections vanilla draws itself: TextureMat[1][2] holds them as one bit each, from section TextureMat[0][2]
// up; TextureMat[0][1] is the camera height, which turns positions back into world heights. A top face right on a section border
// belongs to the section below it.
void main() {
#ifdef WATER_WALL
    fragColor = vec4(wallColor(relativePosition.y + TextureMat[0][1]), 1.0);
    return;
#endif
    // Rough tiles mark the void between floating islands with zero alpha; the island edge lies half way to the void sample.
    if (vertexColor.a < 0.5) {
        DROP;
    }
#ifdef FLOATING
    // On a floating tile waterColor.a is not a coast but the signed distance to the island edge in cells (see
    // TileMeshBuilder): the surface is cut where the distance crosses zero, at the edge itself.
    if (waterColor.a * 16.0 - 15.0 < 0.0) {
        DROP;
    }
#endif
    float horizontal = length(relativePosition.xz);
#ifdef UNDER_DETAIL
    // The patch under vanilla's and the detailed tiles' water is not cut where they draw: it is what shows through their gaps.
    if (TextureMat[1][1] > 0.0 && horizontal > TextureMat[1][1]) {
#else
    if (horizontal < TextureMat[0][0] || (TextureMat[1][1] > 0.0 && horizontal > TextureMat[1][1])) {
#endif
        DROP;
    }
    float fade = TextureMat[1][3] > 0.0 ? clamp((horizontal - TextureMat[1][1] + TextureMat[1][3]) / TextureMat[1][3], 0.0, 1.0) : 0.0;
#ifdef FADE_PASS
    if (fade <= 0.0) {
        DROP;
    }
#elif !defined(WATER_PASS)
    if (fade > 0.0) {
        DROP;
    }
#endif
    if (TextureMat[0][3] > 0.5) {
        float worldY = relativePosition.y + TextureMat[0][1];
        int section = int(floor((worldY - 0.002) / 16.0)) - int(TextureMat[0][2]);
        int drawn = int(TextureMat[1][2] + 0.5);
        if (section >= 0 && section < 24 && ((drawn >> section) & 1) != 0) {
            DROP;
        }
    }
    vec4 fogColor = TextureMat[3][3] > 0.5 ? FogColor : vec4(TextureMat[3].xyz, FogColor.a);
    vec4 surface = vec4(vertexColor.rgb, 1.0);
#ifdef ROUGH
    // The coast value (see TileMeshBuilder#shapeCoasts, -15 to 1) is above zero over water (the second color) and below over land;
    // the coast is drawn where it crosses zero, smoothed over about a pixel or as soft as TextureMat[1][2] asks. Both sides keep their
    // own colors up to it.
    float coast = waterColor.a * 16.0 - 15.0;
    float edge = max(fwidth(coast) * 0.7 + 1.0E-4, TextureMat[1][2]);
    // Both colors have borders of their own (the snow line, forest edges, biomes on land, waters of different temperatures): the
    // color beyond each lies in landThird and waterThird, with the value of the border in alpha (-1 to 1). On land it is as soft as
    // the samples ask, between waters as TextureMat[0][2] asks.
    float landSplit = landThird.a * 2.0 - 1.0;
    float landEdge = max(fwidth(landSplit) * 0.7 + 1.0E-4, edgeSoftness);
    vec3 land = mix(vertexColor.rgb, landThird.rgb, smoothstep(-landEdge, landEdge, landSplit));
    float waterSplit = waterThird.a * 2.0 - 1.0;
    float waterEdge = max(fwidth(waterSplit) * 0.7 + 1.0E-4, TextureMat[0][2]);
    vec3 second = mix(waterColor.rgb, waterThird.rgb, smoothstep(-waterEdge, waterEdge, waterSplit));
#ifdef FLOATING
    // Floating tiles have no water: the land color is the surface, and what is not land was cut above.
    float wet = 0.0;
    surface.rgb = land;
#else
    float wet = smoothstep(-edge, edge, coast);
    // A sharp coast never reaches into a triangle it does not cross: there the value changes along another slope than in the one
    // the coast lies in, and the smoothing would draw a faint line of the other color along the corners close to it.
    if (TextureMat[1][2] <= 0.0 && abs(coastSide) > 0.999) {
        wet = coastSide > 0.0 ? 1.0 : 0.0;
    }
    surface.rgb = mix(land, second, wet);
#endif
#ifndef NO_DISCARD
    // Where vanilla draws, no depth of the rough terrain is kept (see the depth pass), so vanilla is drawn over it wherever it has
    // something, and the rough terrain only fills what vanilla leaves open. Its water stays for that, for the view through vanilla's
    // water past the last chunk it draws. Its ground there and under the detailed tiles is lowered under their blocks (see the vertex
    // shader), so it neither stands out between their blocks nor leaves a hole where it is cut out along the edge of what they draw.
    int owner = leftToOthers(relativePosition);
#ifdef UNDER_DETAIL
    // Only the water within the detailed tiles and vanilla's chunks, drawn under them without depth, see LodRenderer.
    if (wet < 0.5 || horizontal >= TextureMat[2][1]) {
        DROP;
    }
#else
#ifdef FLOATING
    if (owner == 3 || owner == 1 && wet < 0.5) {
        DROP;
    }
#else
    // The ground there lies under their blocks (see the vertex shader). Only where a coast runs through a triangle it may still reach
    // up to the water and show under their translucent water: those parts are left out.
    if (owner == 3 && wet >= 0.5 || (owner == 1 || owner == 3) && wet < 0.5 && abs(coastSide) < 0.999) {
        DROP;
    }
#endif
    // The water there is drawn by the pass under the detailed tiles.
    if (wet >= 0.5 && horizontal < TextureMat[2][1]) {
        DROP;
    }
#endif
    // Debug build: TextureMat[0][3] marks what the rough terrain draws where vanilla draws or is about to: magenta its water under
    // vanilla's chunks, yellow everything along their edge, cyan inside vanilla's distance where vanilla draws nothing yet.
#ifndef UNDER_DETAIL
    // The water under the detailed tiles keeps its color in the debug build too, so the patch is seen as it is.
    if (TextureMat[0][3] > 0.5) {
        if (owner == 1) {
            surface.rgb = vec3(1.0, 0.0, 1.0);
        } else if (owner == 2) {
            surface.rgb = vec3(1.0, 1.0, 0.0);
        } else if (length(relativePosition.xz) < TextureMat[2][1]) {
            surface.rgb = vec3(0.0, 1.0, 1.0);
        }
    }
#endif
#endif
#endif
#ifdef TEXTURED
    // Translucent water (marked with 8) is drawn in its own pass after everything else.
    bool water = (faceCode & 8) != 0;
#ifdef WATER_PASS
    if (!water) {
        DROP;
    }
#else
    if (water) {
        DROP;
    }
#endif
    vec4 texel = blockTexture(fade);
    surface = vec4(texel.rgb * vertexColor.rgb, 1.0);
#ifdef WATER_PASS
    // As opaque as vanilla's water, which takes the alpha of its texture as it is (that of a resource pack too).
    surface.a = texel.a;
    // Towards the edge of the detailed tiles (TextureMat[2][0]) the water turns into the flat, opaque water of the rough terrain
    // beyond it, so there is no second edge where the seabed suddenly stops showing.
    if (TextureMat[2][0] > 0.0) {
        float edge = smoothstep(TextureMat[2][0] * WATER_FADE_START, TextureMat[2][0], horizontal);
        surface = mix(surface, vec4(vertexColor.rgb * spriteAverage() * ROUGH_WATER_SHADE, 1.0), edge);
    }
    // In the fading edge the water has already turned into the rough water beyond it: it stays, so the seabed fading out under it
    // is not seen bare, lighter than both.
#endif
#endif
    // Fog distances are taken per pixel: interpolated from the corners they come out too large inside long triangles, such as the
    // deep skirts closing the gaps between tiles, which would fade into the fog towards their bottom.
    float sphericalDistance = fog_spherical_distance(relativePosition);
    float cylindricalDistance = fog_cylindrical_distance(relativePosition);
    // The LOD terrain's own fog (see LodFog) tells its shape in the end of the clouds, which it has no use for: 1 round, else
    // cylindrical like vanilla's.
    float renderDistance = FogCloudsEnd > 0.5 ? sphericalDistance : cylindricalDistance;
    vec4 color = apply_fog(surface, sphericalDistance, renderDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);

#ifdef FADE_PASS
    surface.a = 1.0 - fade;
#endif
    vec3 result = color.rgb;
    if (TextureMat[3][3] > 0.5 && TextureMat[3][0] > 0.0) {
        float wall = smoothstep(TextureMat[3][0] * WALL_FADE_START, TextureMat[3][0], horizontal);
        result = mix(result, wallColor(relativePosition.y + TextureMat[0][1]), wall);
    }
    fragColor = vec4(result, surface.a);
}
#endif
