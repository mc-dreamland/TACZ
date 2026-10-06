#version 330

// Vanilla cleanup shader. Iris replaces this program, but receives an equivalent dormant branch through
// IrisDepthRestoreShaderMixin. Color writes are disabled by the pipeline; only gl_FragDepth matters.
uniform int tacz_DepthRestoreMode;
uniform sampler2D Sampler3;
uniform sampler2D Sampler5;
uniform sampler2D Sampler6;

out vec4 fragColor;

void main() {
    if (tacz_DepthRestoreMode != 0) {
        vec2 size = max(vec2(textureSize(Sampler3, 0)), vec2(1.0));
        vec2 uv = gl_FragCoord.xy / size;
        if (tacz_DepthRestoreMode == 2) {
            vec2 apertureSize = max(vec2(textureSize(Sampler5, 0)), vec2(1.0));
            vec2 postBodySize = max(vec2(textureSize(Sampler6, 0)), vec2(1.0));
            float apertureDepth = texture(Sampler5, gl_FragCoord.xy / apertureSize).r;
            float postBodyDepth = texture(Sampler6, gl_FragCoord.xy / postBodySize).r;
            // Equal means the invisible ocular is still the nearest hand fragment and may be
            // restored to world depth. A different value means visible scope geometry survived;
            // keep its depth so later water/particles/clouds cannot composite over its color.
            if (postBodyDepth != apertureDepth) {
                discard;
            }
        }
        gl_FragDepth = texture(Sampler3, uv).r;
    }
    fragColor = vec4(0.0);
}
