#version 330

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;
uniform int tacz_ScopeMaskMode;
#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

in vec2 texCoord0;
in float sphericalVertexDistance;
in float cylindricalVertexDistance;
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

bool containsReticlePixel() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 extent = textureSize(Sampler4, 0);
    if (any(notEqual(extent, textureSize(Sampler5, 0)))) return false;
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) return false;

    float depthChange = texelFetch(Sampler4, pixel, 0).r - texelFetch(Sampler5, pixel, 0).r;
    // Reject differences within two steps of a 24-bit depth attachment.
    return depthChange > (2.0 / 16777215.0);
}

vec4 reticleSurface() {
    vec4 albedo = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (albedo.a < ALPHA_CUTOUT) discard;
#endif
#ifdef PER_FACE_LIGHTING
    vec4 tint = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 tint = vertexColor;
#endif
#ifdef DISSOLVE
    if (texture(DissolveMaskSampler, texCoord0).a > tint.a) discard;
    tint.a = 1.0;
#endif
    vec4 shaded = albedo * (ColorModulator * tint);
#ifndef NO_OVERLAY
    shaded.rgb = shaded.rgb * overlayColor.a + overlayColor.rgb * (1.0 - overlayColor.a);
#endif
#ifndef EMISSIVE
    shaded *= lightMapColor;
#endif
    return shaded;
}

void main() {
    if (tacz_ScopeMaskMode != 0 && !containsReticlePixel()) discard;
    fragColor = apply_fog(reticleSurface(), sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
