package com.tacz.guns.mixin.client.iris;

import com.tacz.guns.GunMod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Adds depth-restore and ocular screen-space mask branches to Iris hand fragment shaders.
 *
 * <p>Does not import Iris types: {@code @Mixin(targets=...)} so this class compiles without Iris
 * on the classpath. The companion plugin refuses to apply the mixin when Iris is absent.</p>
 *
 * <p>Only first-person HAND programs are patched. Injecting dormant {@code discard}/{@code gl_FragDepth}
 * into world gbuffers makes some NVIDIA drivers compile those programs differently.</p>
 *
 * Semantic source: Fabric 26.1.2 {@code IrisDepthRestoreShaderMixin} (game shader text, not MUKSC).
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderCreator", remap = false)
public abstract class IrisDepthRestoreShaderMixin {
    private static boolean tacz$loggedPatch;

    @ModifyArgs(
            method = "link",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;createShader(Ljava/lang/String;Lnet/irisshaders/iris/gl/shader/ShaderType;Ljava/lang/String;)I",
                    ordinal = 4
            ),
            require = 0
    )
    private static void tacz$injectScopeBranchesIntoFragmentShader(Args args) {
        String name = (String) args.get(0);
        String source = (String) args.get(2);
        args.set(2, tacz$patchHandFragmentShader(name, source));
    }

    private static String tacz$patchHandFragmentShader(String name, String source) {
        if (!tacz$isHandProgram(name) || source == null || source.contains("tacz_ScopeMaskMode")) {
            return source;
        }
        int main = source.indexOf("void main");
        if (main < 0) {
            return source;
        }
        int brace = source.indexOf('{', main);
        if (brace < 0) {
            return source;
        }

        int declarationPos = 0;
        if (source.startsWith("#version")) {
            int lineEnd = source.indexOf('\n');
            if (lineEnd >= 0) {
                declarationPos = lineEnd + 1;
            }
        }
        String depthtex2Declaration = source.contains("depthtex2")
                ? ""
                : "uniform sampler2D depthtex2;\n";
        String declarations = "\n// TACZ ocular scope branches; dormant for ordinary hand draws\n"
                + "uniform int tacz_DepthRestoreMode;\n"
                + "uniform int tacz_ScopeMaskMode;\n"
                + "uniform sampler2D Sampler5;\n"
                + "uniform sampler2D Sampler6;\n"
                + depthtex2Declaration;
        String restoreBranch = "\n    gl_FragDepth = gl_FragCoord.z;\n"
                + "    if (tacz_DepthRestoreMode != 0) {\n"
                + "        if (tacz_DepthRestoreMode == 2) {\n"
                + "            vec2 tacz_apertureUv = gl_FragCoord.xy / max(vec2(textureSize(Sampler5, 0)), vec2(1.0));\n"
                + "            vec2 tacz_postBodyUv = gl_FragCoord.xy / max(vec2(textureSize(Sampler6, 0)), vec2(1.0));\n"
                + "            float tacz_apertureDepth = texture(Sampler5, tacz_apertureUv).r;\n"
                + "            float tacz_postBodyDepth = texture(Sampler6, tacz_postBodyUv).r;\n"
                + "            if (tacz_postBodyDepth != tacz_apertureDepth) {\n"
                + "                discard;\n"
                + "            }\n"
                + "        }\n"
                + "        vec2 tacz_depthSize = max(vec2(textureSize(depthtex2, 0)), vec2(1.0));\n"
                + "        vec2 tacz_depthUv = gl_FragCoord.xy / tacz_depthSize;\n"
                + "        gl_FragDepth = texture(depthtex2, tacz_depthUv).r;\n"
                + "        return;\n"
                + "    }\n";
        String maskBranch = "\n    if (tacz_ScopeMaskMode != 0) {\n"
                + "        vec2 tacz_maskWorldUv = gl_FragCoord.xy / max(vec2(textureSize(depthtex2, 0)), vec2(1.0));\n"
                + "        vec2 tacz_maskApertureUv = gl_FragCoord.xy / max(vec2(textureSize(Sampler5, 0)), vec2(1.0));\n"
                + "        float tacz_maskWorldDepth = texture(depthtex2, tacz_maskWorldUv).r;\n"
                + "        float tacz_maskApertureDepth = texture(Sampler5, tacz_maskApertureUv).r;\n"
                + "        bool tacz_insideOcular = tacz_maskApertureDepth < tacz_maskWorldDepth - 1.0e-6;\n"
                + "        if ((tacz_ScopeMaskMode == 1 && !tacz_insideOcular)\n"
                + "                || (tacz_ScopeMaskMode == 2 && tacz_insideOcular)) {\n"
                + "            discard;\n"
                + "        }\n"
                + "    }\n";

        String withDeclarations = source.substring(0, declarationPos)
                + declarations + source.substring(declarationPos);
        int adjustedBrace = brace + declarations.length();
        String patched = withDeclarations.substring(0, adjustedBrace + 1)
                + restoreBranch + maskBranch + withDeclarations.substring(adjustedBrace + 1);
        if (!tacz$loggedPatch) {
            tacz$loggedPatch = true;
            GunMod.LOGGER.info("[TACZ Scope] Injected dormant depth-restore and ocular-mask branches into Iris hand shaders.");
        }
        return patched;
    }

    private static boolean tacz$isHandProgram(String name) {
        if (name == null) {
            return false;
        }
        return name.equals("gbuffers_hand")
                || name.equals("gbuffers_hand_water")
                || name.endsWith("/gbuffers_hand")
                || name.endsWith("/gbuffers_hand_water")
                || name.equals("hand")
                || name.startsWith("hand_");
    }
}
