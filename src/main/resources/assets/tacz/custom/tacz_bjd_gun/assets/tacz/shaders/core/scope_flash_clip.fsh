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

bool lensOccludesEffect() {
    if (tacz_ScopeMaskMode != 2) return false;
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 extent = textureSize(Sampler4, 0);
    // Invalid copies leave the ordinary gun/flash visible.
    if (any(notEqual(textureSize(Sampler5, 0), extent))) return false;
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) return false;

    float separation = texelFetch(Sampler4, pixel, 0).r - texelFetch(Sampler5, pixel, 0).r;
    return separation > (2.0 / 16777215.0);
}

void main() {
    if (lensOccludesEffect()) discard;

    vec4 surface = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (surface.a < ALPHA_CUTOUT) discard;
#endif
#ifdef PER_FACE_LIGHTING
    vec4 illumination = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 illumination = vertexColor;
#endif
#ifdef DISSOLVE
    if (illumination.a < texture(DissolveMaskSampler, texCoord0).a) discard;
    illumination.a = 1.0;
#endif
    surface *= ColorModulator * illumination;
#ifndef NO_OVERLAY
    surface.rgb = overlayColor.rgb * (1.0 - overlayColor.a) + surface.rgb * overlayColor.a;
#endif
#ifndef EMISSIVE
    surface *= lightMapColor;
#endif
    fragColor = apply_fog(surface, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
