package com.bbscg.mixin;

import mchorse.bbs_mod.math.molang.MolangParser;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes single-character variable names in the MoLang parser.
 *
 * <p>Stock 2.7's {@code getVariable} begins with {@code name.charAt(1) == '.'} to expand
 * {@code q.*}/{@code v.*} short-hands — without a length check. Every one-character
 * variable ({@code x}, {@code y}, {@code n}, {@code l}, ...) — legal per the Bedrock MoLang
 * spec and used pervasively by hand-authored effects like lightning bolts — throws
 * StringIndexOutOfBoundsException there, which {@code parseDataSilently} swallows and
 * replaces with ZERO. The whole expression tree then collapses to zero on both the
 * assignment and the consumer side, silently.</p>
 *
 * <p>This defends only the out-of-bounds read: an index past the end yields {@code '\0'}
 * (never {@code '.'}), so the prefix expansion is skipped and the name flows through the
 * regular local/global variable lookup untouched. In-bounds reads are returned verbatim,
 * so stock behaviour for everything else is bit-for-bit unchanged — this is a pure
 * crash fix, not a behaviour change, and applies globally (model animation and CEM
 * expressions benefit the same way).</p>
 */
@Mixin(MolangParser.class)
public abstract class MolangParserMixin
{
    @Redirect(
        method = "getVariable",
        at = @At(value = "INVOKE", target = "Ljava/lang/String;charAt(I)C")
    )
    private char bbscgSafeCharAt(String name, int index)
    {
        return index < name.length() ? name.charAt(index) : '\0';
    }
}
