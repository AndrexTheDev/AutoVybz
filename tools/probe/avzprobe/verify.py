"""Verification pass (roadmap W6): compare a measurement *through* the active EQ with its prediction.

Mirror of `measurement/Verification.kt`. The interesting part is not the RMS arithmetic but the verdict:
the app must be able to say "this is not what we predicted" when the chain lied (EQ not applied, another
speaker, the limiter clamped everything) and "as predicted" when it did.
"""

from __future__ import annotations

import math

import numpy as np

from . import dsp

PREDICTION_TOLERANCE_DB = 1.5
MIN_AUDIBLE_CHANGE_DB = 0.5
MIN_CORRELATION = 0.5
NORMALISATION_LO_HZ = 300.0
NORMALISATION_HI_HZ = 3000.0
JUDGE_LOW_FACTOR = 1.41

AS_PREDICTED = "AS_PREDICTED"
BETTER_THAN_PREDICTED = "BETTER_THAN_PREDICTED"
WEAKER_THAN_PREDICTED = "WEAKER_THAN_PREDICTED"
UNEXPECTED = "UNEXPECTED"
NO_CHANGE = "NO_CHANGE"


def correlation(a: np.ndarray, b: np.ndarray) -> float:
    """Pearson correlation; 0 when either series has no variance."""
    da = a - np.mean(a)
    db = b - np.mean(b)
    den = math.sqrt(float(np.sum(da * da)) * float(np.sum(db * db)))
    return 0.0 if den <= 1e-12 else float(np.sum(da * db)) / den


def compare(before, after, plan) -> dict:
    """`Verification.compare`: [before]/[after] are `dsp.MeasurementResult`, [plan] the applied `AutoEqResult`."""
    grid = before.frequencies
    predicted = np.array(plan.filter_response_db, dtype=float)
    predicted = predicted - dsp.band_mean(grid, predicted, NORMALISATION_LO_HZ, NORMALISATION_HI_HZ)
    # Level alignment: the analyzer already normalises both curves, but an offset that survived (another
    # capture gain, a run at a different volume) must not look like a tonal change.
    measured = after.smoothed_db - before.smoothed_db
    measured = measured - dsp.band_mean(grid, measured, NORMALISATION_LO_HZ, NORMALISATION_HI_HZ)

    lo = max(plan.low_limit_hz * JUDGE_LOW_FACTOR, NORMALISATION_LO_HZ / 2.0)
    judge = (grid >= lo) & (grid <= plan.high_limit_hz)
    if not np.any(judge):
        raise ValueError("empty judgement band")

    def rms(values: np.ndarray) -> float:
        return float(math.sqrt(float(np.mean(values[judge] ** 2))))

    error = measured - predicted
    prediction_error = rms(error)
    measured_change = rms(measured)
    predicted_change = rms(predicted)

    # Residuals against the plan's own target curve, level-aligned in the judged band (a constant offset
    # is volume, not tonal balance).
    def aligned_residual(curve: np.ndarray) -> float:
        d = curve - plan.target_db
        d = d[judge] - float(np.mean(d[judge]))
        return float(math.sqrt(float(np.mean(d * d))))

    residual_before = aligned_residual(before.smoothed_db)
    residual_after = aligned_residual(after.smoothed_db)
    residual_predicted = aligned_residual(plan.predicted_db)

    corr = correlation(measured[judge], predicted[judge])
    if predicted_change < MIN_AUDIBLE_CHANGE_DB and measured_change < MIN_AUDIBLE_CHANGE_DB:
        verdict = AS_PREDICTED
    elif measured_change < MIN_AUDIBLE_CHANGE_DB:
        verdict = NO_CHANGE
    elif prediction_error <= PREDICTION_TOLERANCE_DB:
        verdict = AS_PREDICTED
    elif corr < MIN_CORRELATION:
        verdict = UNEXPECTED
    elif measured_change < predicted_change:
        verdict = WEAKER_THAN_PREDICTED
    else:
        verdict = BETTER_THAN_PREDICTED

    return {
        "correlation": corr,
        "measured_change_db": measured_change,
        "predicted_change_db": predicted_change,
        "prediction_error_db": prediction_error,
        "residual_before_db": residual_before,
        "residual_after_db": residual_after,
        "residual_predicted_db": residual_predicted,
        "measured_improvement_db": residual_before - residual_after,
        "predicted_improvement_db": residual_before - residual_predicted,
        "judge_low_hz": float(grid[judge][0]),
        "judge_high_hz": float(grid[judge][-1]),
        "verdict": verdict,
    }
