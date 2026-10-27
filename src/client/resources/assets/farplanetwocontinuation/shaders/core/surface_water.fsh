#version 330
#extension GL_ARB_separate_shader_objects : require

#define SURFACE_FRAGMENT
#include <minecraft:fog.glsl>
#include <farplanetwocontinuation:surface.glsl>

layout(location = 0) in vec3 relativePosition;
layout(location = 1) in vec4 vertexColor;
layout(location = 2) in vec4 secondColor;
layout(location = 3) in float softness;
layout(location = 4) in float waterLevel;

layout(location = 0) out vec4 fragColor;

// The water at its level, over the seabed: as opaque as its depth makes it (see SurfaceMesher), its borders between waters of other
// temperatures as soft as the settings ask.
void main() {
    vec3 color = mix(beyondBorder(vertexColor.rgb, secondColor, Params.y), TileTint.rgb, TileTint.a);
    fragColor = vec4(lodFog(color, relativePosition), vertexColor.a);
}
