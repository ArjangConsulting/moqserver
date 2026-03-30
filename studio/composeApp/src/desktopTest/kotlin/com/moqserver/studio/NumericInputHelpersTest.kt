package com.moqserver.studio

import com.moqserver.studio.endpointdetail.NumericInputUpdate
import com.moqserver.studio.endpointdetail.acceptedDoubleInput
import com.moqserver.studio.endpointdetail.acceptedIntInput
import com.moqserver.studio.endpointdetail.acceptedPositiveIntOrNullInput
import kotlin.test.Test
import kotlin.test.assertEquals

class NumericInputHelpersTest {

    @Test
    fun `acceptedIntInput ignores invalid text`() {
        assertEquals(NumericInputUpdate.Ignored, acceptedIntInput("200a", currentValue = 200))
    }

    @Test
    fun `acceptedIntInput ignores unchanged values`() {
        assertEquals(NumericInputUpdate.Ignored, acceptedIntInput("200", currentValue = 200))
    }

    @Test
    fun `acceptedIntInput accepts changed values`() {
        assertEquals(NumericInputUpdate.Accepted(201), acceptedIntInput("201", currentValue = 200))
    }

    @Test
    fun `acceptedDoubleInput ignores invalid text`() {
        assertEquals(NumericInputUpdate.Ignored, acceptedDoubleInput("5.5x", currentValue = 5.5))
    }

    @Test
    fun `acceptedDoubleInput accepts changed values`() {
        assertEquals(NumericInputUpdate.Accepted(7.5), acceptedDoubleInput("7.5", currentValue = 5.5))
    }

    @Test
    fun `acceptedPositiveIntOrNullInput ignores invalid text`() {
        assertEquals(NumericInputUpdate.Ignored, acceptedPositiveIntOrNullInput("10x", currentValue = 10))
    }

    @Test
    fun `acceptedPositiveIntOrNullInput clears non-positive values`() {
        assertEquals(NumericInputUpdate.Accepted(null), acceptedPositiveIntOrNullInput("0", currentValue = 10))
    }

    @Test
    fun `acceptedPositiveIntOrNullInput ignores unchanged normalized null values`() {
        assertEquals(NumericInputUpdate.Ignored, acceptedPositiveIntOrNullInput("0", currentValue = null))
    }
}
