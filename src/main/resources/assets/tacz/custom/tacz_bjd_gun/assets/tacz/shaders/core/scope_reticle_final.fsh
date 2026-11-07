#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform int tacz_ScopeMaskMode;
// Java detects this active uniform to select private post-composite depth copies.
uniform int tacz_ScopeFinalOverlay;
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

bool finalPixelVisible() {
    if (tacz_ScopeFinalOverlay != 1 || tacz_ScopeMaskMode != 1) return false;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 extent = textureSize(Sampler5, 0);
    if (any(notEqual(textureSize(Sampler4, 0), extent))) return false;
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) return false;

    float scene = texelFetch(Sampler4, pixel, 0).r;
    float lens = texelFetch(Sampler5, pixel, 0).r;
    return scene - lens > (2.0 / 16777215.0);
}

void main() {
    if (!finalPixelVisible()) discard;

    vec4 sampleColor = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (sampleColor.a < ALPHA_CUTOUT) discard;
#endif
#ifdef PER_FACE_LIGHTING
    vec4 modulation = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 modulation = vertexColor;
#endif
#ifdef DISSOLVE
    if (modulation.a < texture(DissolveMaskSampler, texCoord0).a) discard;
    modulation.a = 1.0;
#endif
    sampleColor *= modulation * ColorModulator;
#ifndef NO_OVERLAY
    sampleColor.rgb = overlayColor.rgb * (1.0 - overlayColor.a) + sampleColor.rgb * overlayColor.a;
#endif
#ifndef EMISSIVE
    sampleColor *= lightMapColor;
#endif
    // The destination already contains the composited world; no world fog here.
    fragColor = sampleColor;
}
