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

// The ground: land, and under the water the seabed. Nothing is dropped, so the GPU rejects hidden fragments before shading them.
void main() {
    vec3 color = beyondBorder(vertexColor.rgb, secondColor, softness) * seabedShade(waterLevel - relativePosition.y);
    color = mix(color, TileTint.rgb, TileTint.a);
    fragColor = vec4(lodFog(color, relativePosition), 1.0);
}
