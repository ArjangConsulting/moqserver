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
