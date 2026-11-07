#version 330

// ScopeDepthCopyState supplies full-size, unfiltered depth copies.
uniform sampler2D Sampler3;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;
uniform int tacz_DepthRestoreMode;

out vec4 fragColor;

void main() {
    if (tacz_DepthRestoreMode < 1 || tacz_DepthRestoreMode > 2) discard;

    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 extent = textureSize(Sampler3, 0);
    if (any(lessThan(pixel, ivec2(0))) || any(greaterThanEqual(pixel, extent))) discard;

    if (tacz_DepthRestoreMode == 2) {
        if (any(notEqual(textureSize(Sampler5, 0), extent)) ||
            any(notEqual(textureSize(Sampler6, 0), extent))) discard;

        float beforeBody = texelFetch(Sampler5, pixel, 0).r;
        float afterBody = texelFetch(Sampler6, pixel, 0).r;
        // A changed texel belongs to visible geometry and must keep its depth.
        if (afterBody != beforeBody) discard;
    }

    fragColor = vec4(0.0);
    gl_FragDepth = texelFetch(Sampler3, pixel, 0).r;
}
