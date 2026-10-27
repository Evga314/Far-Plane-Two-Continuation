// The new renderer's rough terrain (see OwnPassOutput): what its shaders share.

// Written once a frame, see OwnPassOutput.FrameData.
layout(std140) uniform SurfaceFrame {
    // The camera's turn: positions come relative to the camera.
    mat4 ViewMat;
    // Vanilla's projection, for the depth the terrain leaves behind for vanilla, see surface_depth.fsh.
    mat4 VanillaProjMat;
    // How brightly the terrain is lit.
    vec4 Light;
    // rgb: the color distant terrain fades into under skies without the overworld's sky disc; a: 1 under the overworld sky.
    vec4 Sky;
    // x: how far from the camera the tree tops start to rise from the ground; y: how soft the borders between waters are drawn;
    // z: 1 for depth from 0 to 1; w: how far the depth left for vanilla is pushed away from the camera.
    vec4 Params;
};

// Written per tile, see OwnPassOutput.TileData.
layout(std140) uniform SurfaceTile {
    // xyz: the tile's north-west corner at its base height, from the camera; w: blocks per unit of x and z.
    vec4 TileOffset;
    // The debug build tints the ring the rough terrain draws under vanilla's edge (see OwnershipMap): rgb, and how much.
    vec4 TileTint;
    // x: how much farther from the camera the tile is put for the depth test (see FrameDrawList.DEPTH_PUSH).
    vec4 TileParams;
};

#ifdef SURFACE_FRAGMENT
// The seabed is darker the deeper under the water it lies, as vanilla's sky light falls by one a block of water.
const float SEABED_SHALLOW = 0.8;
const float SEABED_DEEP = 0.35;
const float SEABED_DARKENING_DEPTH = 12.0;

float seabedShade(float depth) {
    return depth <= 0.0 ? 1.0 : mix(SEABED_DEEP, SEABED_SHALLOW, exp(-depth / SEABED_DARKENING_DEPTH));
}

// The color beyond a border (the snow line, forest edges, biomes, waters of other temperatures) where its value, in the alpha of
// the second color, is above zero: sharp, smoothed over about a pixel, or as soft as asked, in units of that value.
vec3 beyondBorder(vec3 color, vec4 second, float softness) {
    float value = second.a * 2.0 - 1.0;
    float edge = max(fwidth(value) * 0.7 + 1.0E-4, softness);
    return mix(color, second.rgb, smoothstep(-edge, edge, value));
}

// The one fog of the distant terrain, in the air and under water alike: vanilla's, with the distances of the LOD terrain (see
// LodFog), whose end of the clouds tells its shape: 1 round, else cylindrical like vanilla's.
vec3 lodFog(vec3 color, vec3 relative) {
    vec4 fogColor = Sky.a > 0.5 ? FogColor : vec4(Sky.rgb, FogColor.a);
    float spherical = fog_spherical_distance(relative);
    float renderDistance = FogCloudsEnd > 0.5 ? spherical : fog_cylindrical_distance(relative);
    return apply_fog(vec4(color, 1.0), spherical, renderDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart,
        FogRenderDistanceEnd, fogColor).rgb;
}
#endif
