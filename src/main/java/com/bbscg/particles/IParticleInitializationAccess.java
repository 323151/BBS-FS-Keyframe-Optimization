package com.bbscg.particles;

import mchorse.bbs_mod.math.molang.expressions.MolangExpression;

/**
 * Duck interface added onto {@code ParticleComponentInitialization} by the mixin, giving the
 * emitter-side execution path access to the Snowstorm/Blockbuster extension field
 * {@code particle_update_expression} (a Molang block that runs once per particle per tick,
 * and once more per render, before any consumer component reads the variables it assigns).
 */
public interface IParticleInitializationAccess
{
    MolangExpression bbscgGetParticleUpdate();
}
