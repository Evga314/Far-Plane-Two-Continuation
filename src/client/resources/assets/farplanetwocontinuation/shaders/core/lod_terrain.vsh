#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec3 relativePosition;
layout(location = 4) out vec4 viewPosition;

void main() {
    vec3 pos = Position + ModelOffset;
    viewPosition = ModelViewMat * vec4(pos, 1.0);
#ifdef DEPTH_ONLY
    gl_Position = ProjMat * viewPosition;
#else
    // Where two layers cover the same place, the more detailed one wins: less detailed tiles move away from the camera for the
    // depth test only (TextureMat[2][2] is slightly above 1 for them). Scaling along the view ray keeps every pixel where it is.
    gl_Position = ProjMat * vec4(viewPosition.xyz * TextureMat[2][2], 1.0);
#endif

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * ColorModulator;
    relativePosition = pos;
}
