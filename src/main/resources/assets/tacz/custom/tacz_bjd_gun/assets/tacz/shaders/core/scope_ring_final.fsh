#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

in vec2 texCoord0;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorFront;
in vec4 vertexPerFaceColorBack;
#else
in vec4 vertexColor;
#endif
#ifndef EMISSIVE
in vec4 lightMapColor;
#endif
#ifndef NO_OVERLAY
in vec4 overlayColor;
#endif

out vec4 fragColor;

vec4 ringTint() {
#ifdef PER_FACE_LIGHTING
    return gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    return vertexColor;
#endif
}

void main() {
    vec4 ring = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (ring.a < ALPHA_CUTOUT) discard;
#endif
    vec4 tint = ringTint();
#ifdef DISSOLVE
    if (texture(DissolveMaskSampler, texCoord0).a > tint.a) discard;
    tint.a = 1.0;
#endif
    ring *= tint * ColorModulator;
#ifndef NO_OVERLAY
    ring.rgb = ring.rgb * overlayColor.a + overlayColor.rgb * (1.0 - overlayColor.a);
#endif
#ifndef EMISSIVE
    ring *= lightMapColor;
#endif
    // Foreground ring is submitted after world compositing, without another fog pass.
    fragColor = ring;
}
