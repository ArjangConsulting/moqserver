package com.moqserver.studio.endpointdetail

internal sealed interface NumericInputUpdate<out T> {
    data object Ignored : NumericInputUpdate<Nothing>

    data class Accepted<T>(val value: T) : NumericInputUpdate<T>
}

internal fun acceptedIntInput(text: String, currentValue: Int): NumericInputUpdate<Int> {
    val parsed = text.toIntOrNull() ?: return NumericInputUpdate.Ignored
    return if (parsed == currentValue) NumericInputUpdate.Ignored else NumericInputUpdate.Accepted(parsed)
}

internal fun acceptedDoubleInput(text: String, currentValue: Double): NumericInputUpdate<Double> {
    val parsed = text.toDoubleOrNull() ?: return NumericInputUpdate.Ignored
    return if (parsed == currentValue) NumericInputUpdate.Ignored else NumericInputUpdate.Accepted(parsed)
}

internal fun acceptedPositiveIntOrNullInput(text: String, currentValue: Int?): NumericInputUpdate<Int?> {
    val parsed = text.toIntOrNull() ?: return NumericInputUpdate.Ignored
    val normalized = parsed.takeIf { it > 0 }
    return if (normalized == currentValue) NumericInputUpdate.Ignored else NumericInputUpdate.Accepted(normalized)
}

/** For an optional count where `0` means something: empty text clears it, a negative number is ignored. */
internal fun acceptedOptionalNonNegativeIntInput(text: String, currentValue: Int?): NumericInputUpdate<Int?> {
    val parsed = if (text.isBlank()) {
        null
    } else {
        text.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return NumericInputUpdate.Ignored
    }
    return if (parsed == currentValue) NumericInputUpdate.Ignored else NumericInputUpdate.Accepted(parsed)
}

/** For a count that must stay within [minimum]..[maximum]; anything else is ignored. */
internal fun acceptedBoundedIntInput(
    text: String,
    currentValue: Int,
    minimum: Int,
    maximum: Int = Int.MAX_VALUE,
): NumericInputUpdate<Int> {
    val parsed = text.trim().toIntOrNull()?.takeIf { it in minimum..maximum } ?: return NumericInputUpdate.Ignored
    return if (parsed == currentValue) NumericInputUpdate.Ignored else NumericInputUpdate.Accepted(parsed)
}
