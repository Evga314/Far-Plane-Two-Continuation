#version 330
#extension GL_ARB_separate_shader_objects : require

#include <farplanetwocontinuation:surface.glsl>

layout(location = 5) in vec4 vanillaClip;

layout(location = 0) out vec4 fragColor;

// The depth of the ground in vanilla's projection, a little farther away, so vanilla's things behind the distant terrain are hidden
// and vanilla's own blocks at the same place always win.
void main() {
    float ndc = vanillaClip.z / vanillaClip.w;
    gl_FragDepth = clamp(Params.z > 0.5 ? ndc : ndc * 0.5 + 0.5, 0.0, 1.0);
    fragColor = vec4(0.0);
}
