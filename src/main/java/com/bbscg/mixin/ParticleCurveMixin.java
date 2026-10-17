package com.bbscg.mixin;

import com.bbscg.particles.SnowstormCompat;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.data.types.StringType;
import mchorse.bbs_mod.math.molang.MolangParser;
import mchorse.bbs_mod.math.molang.expressions.MolangExpression;
import mchorse.bbs_mod.particles.ParticleCurve;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adds the two curve types Snowstorm supports that stock BBS 2.7 lacks:
 *
 * <ul>
 *     <li>{@code bezier} — a cubic bezier with exactly four control points in {@code nodes},
 *     evaluated at {@code input / horizontal_range}. Stock BBS silently degrades this to
 *     {@code linear} (the curve enum only knows linear/catmull_rom), producing visibly
 *     different motion.</li>
 *     <li>{@code bezier_chain} — a piecewise bezier whose {@code nodes} is a <b>map</b> keyed
 *     by time, each node holding {@code value} (or {@code left_value}/{@code right_value}) and
 *     {@code slope} (or {@code left_slope}/{@code right_slope}). Stock BBS can't even parse
 *     this shape (it expects a list). Evaluated as a piecewise cubic herite spline, matching
 *     Snowstorm/Wintersky.</li>
 * </ul>
 *
 * <p>Both are no-ops while the {@code snowstorm_particles} setting is off.</p>
 */
@Mixin(ParticleCurve.class)
public abstract class ParticleCurveMixin
{
    @Shadow
    public MolangExpression input;

    @Shadow
    public MolangExpression range;

    @Shadow
    public List<MolangExpression> nodes;

    /* Whether the parsed "type" was "bezier" (the enum fell back to LINEAR, so we remember) */
    @Unique
    private boolean bbscgBezier;

    /* Parsed bezier_chain nodes (sorted by time); @Unique field initializers are dropped by
     * mixin merging, so this list is created lazily. */
    @Unique
    private List<ChainNode> bbscgChain;

    private List<ChainNode> bbscgChain()
    {
        if (this.bbscgChain == null)
        {
            this.bbscgChain = new ArrayList<>();
        }

        return this.bbscgChain;
    }

    private static class ChainNode
    {
        double time;
        MolangExpression leftValue;
        MolangExpression rightValue;
        MolangExpression leftSlope;
        MolangExpression rightSlope;

        /* The original node JSON, kept for lossless round-tripping */
        BaseType raw;
    }

    @Inject(method = "fromData", at = @At("HEAD"), cancellable = true)
    private void bbscgParseCurveTypes(MapType data, MolangParser parser, CallbackInfo ci)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        /* Re-parse safety: clear any leftover state from a previous fromData on this curve */
        this.bbscgBezier = false;

        if (this.bbscgChain != null)
        {
            this.bbscgChain.clear();
        }

        String type = data.getString("type");

        if ("bezier".equals(type))
        {
            /* Remember the original type; let the stock parser handle input/nodes as usual
             * (nodes is a regular list of four control point expressions). */
            this.bbscgBezier = true;

            return;
        }

        if (!"bezier_chain".equals(type))
        {
            return;
        }

        /* bezier_chain can't go through the stock parser at all (its nodes is a map), so
         * parse it completely here and cancel the stock code path. */
        BaseType input = data.get("input");

        if (input != null)
        {
            this.input = parser.parseDataSilently(input);
        }

        BaseType nodes = data.get("nodes");

        if (nodes != null && nodes.isMap())
        {
            for (Map.Entry<String, BaseType> entry : nodes.asMap())
            {
                BaseType nodeData = entry.getValue();

                if (!nodeData.isMap())
                {
                    continue;
                }

                double time;

                try
                {
                    time = Double.parseDouble(entry.getKey());
                }
                catch (NumberFormatException e)
                {
                    continue;
                }

                MapType node = nodeData.asMap();
                ChainNode node2 = new ChainNode();

                node2.time = time;
                node2.raw = nodeData;
                node2.leftValue = bbscgParseNodeExpression(parser, node, "left_value", "value");
                node2.rightValue = bbscgParseNodeExpression(parser, node, "right_value", "value");
                node2.leftSlope = bbscgParseNodeExpression(parser, node, "left_slope", "slope");
                node2.rightSlope = bbscgParseNodeExpression(parser, node, "right_slope", "slope");

                this.bbscgChain().add(node2);
            }
        }

        this.bbscgChain().sort((a, b) -> Double.compare(a.time, b.time));

        ci.cancel();
    }

    @Unique
    private static MolangExpression bbscgParseNodeExpression(MolangParser parser, MapType node, String... keys)
    {
        for (String key : keys)
        {
            if (node.has(key))
            {
                return parser.parseDataSilently(node.get(key));
            }
        }

        return MolangParser.ZERO;
    }

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void bbscgComputeCurveTypes(CallbackInfoReturnable<Double> cir)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        if (this.bbscgBezier && this.nodes.size() >= 4)
        {
            double range = this.range != null ? this.range.get() : 1;
            double factor = range == 0 ? 0 : this.input.get() / range;

            factor = Math.max(0, Math.min(1, factor));

            double u = 1 - factor;
            double p0 = this.nodes.get(0).get();
            double p1 = this.nodes.get(1).get();
            double p2 = this.nodes.get(2).get();
            double p3 = this.nodes.get(3).get();

            cir.setReturnValue(
                u * u * u * p0
                + 3 * u * u * factor * p1
                + 3 * u * factor * factor * p2
                + factor * factor * factor * p3
            );
        }
        else if (this.bbscgChain != null && !this.bbscgChain.isEmpty())
        {
            List<ChainNode> chain = this.bbscgChain;
            double value = this.input != null ? this.input.get() : 0;
            int last = chain.size() - 1;

            if (value <= chain.get(0).time)
            {
                cir.setReturnValue((double) chain.get(0).leftValue.get());

                return;
            }

            if (value >= chain.get(last).time)
            {
                cir.setReturnValue((double) chain.get(last).rightValue.get());

                return;
            }

            for (int i = 0; i < last; i++)
            {
                ChainNode a = chain.get(i);
                ChainNode b = chain.get(i + 1);

                if (value >= a.time && value <= b.time)
                {
                    double dt = b.time - a.time;
                    double t = dt == 0 ? 0 : (value - a.time) / dt;
                    double p0 = a.rightValue.get();
                    double p1 = b.leftValue.get();
                    double m0 = a.rightSlope.get() * dt;
                    double m1 = b.leftSlope.get() * dt;

                    double t2 = t * t;
                    double t3 = t2 * t;

                    /* Cubic hermite basis */
                    double h00 = 2 * t3 - 3 * t2 + 1;
                    double h10 = t3 - 2 * t2 + t;
                    double h01 = -2 * t3 + 3 * t2;
                    double h11 = t3 - t2;

                    cir.setReturnValue(h00 * p0 + h10 * m0 + h01 * p1 + h11 * m1);

                    return;
                }
            }
        }
    }

    @Inject(method = "toData", at = @At("HEAD"), cancellable = true)
    private void bbscgWriteCurveTypes(CallbackInfoReturnable<MapType> cir)
    {
        if (!SnowstormCompat.isEnabled())
        {
            return;
        }

        if (this.bbscgBezier)
        {
            MapType data = new MapType();

            data.put("type", new StringType("bezier"));

            if (this.input != null) data.put("input", this.input.toData());
            if (this.range != null) data.put("horizontal_range", this.range.toData());

            if (!this.nodes.isEmpty())
            {
                ListType nodes = new ListType();

                for (MolangExpression expression : this.nodes)
                {
                    nodes.add(expression.toData());
                }

                data.put("nodes", nodes);
            }

            cir.setReturnValue(data);
        }
        else if (this.bbscgChain != null && !this.bbscgChain.isEmpty())
        {
            MapType data = new MapType();
            MapType nodes = new MapType();

            data.put("type", new StringType("bezier_chain"));

            if (this.input != null) data.put("input", this.input.toData());

            for (ChainNode node : this.bbscgChain)
            {
                /* Round-trip the raw node JSON so split values/slopes are preserved */
                nodes.put(String.valueOf(node.time), node.raw != null ? node.raw : new MapType());
            }

            data.put("nodes", nodes);

            cir.setReturnValue(data);
        }
    }
}
