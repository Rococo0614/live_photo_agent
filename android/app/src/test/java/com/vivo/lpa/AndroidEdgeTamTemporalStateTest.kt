package com.vivo.lpa

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidEdgeTamTemporalStateTest {
    @Test
    fun memoryIsConditionThenOldestToNewestWhilePointersAreNewestFirst() {
        val state = AndroidEdgeTamTemporalState(pointer(99))
        for (index in 0..8) {
            state.append(index, memory(index), pointer(index), modelObjectPresent = true)
        }

        val inputs = state.inputsForNextFrame()

        assertEquals(7, inputs.spatialMemoryCount)
        assertEquals(9, inputs.objectPointerCount)
        assertEquals("memory_attention_7_9_o3.bin", inputs.contextFileName)
        assertEquals(listOf(0, 3, 4, 5, 6, 7, 8), memoryMarkers(inputs.maskMemoryValues))
        assertEquals(listOf(0, 8, 7, 6, 5, 4, 3, 2, 1),
            pointerMarkers(inputs.objectPointerValues))
    }

    @Test
    fun pointerBankCapsAtSixteenWithoutChangingMemoryCount() {
        val state = AndroidEdgeTamTemporalState(pointer(99))
        for (index in 0..20) {
            state.append(index, memory(index), pointer(index), modelObjectPresent = true)
        }

        val inputs = state.inputsForNextFrame()

        assertEquals(7, inputs.spatialMemoryCount)
        assertEquals(16, inputs.objectPointerCount)
        assertEquals("memory_attention_7_16_o3.bin", inputs.contextFileName)
        assertEquals(listOf(0, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6),
            pointerMarkers(inputs.objectPointerValues))
    }

    @Test
    fun absentObjectUsesPinnedNoObjectPointer() {
        val noObject = pointer(77)
        val state = AndroidEdgeTamTemporalState(noObject)
        state.append(0, memory(0), pointer(0), modelObjectPresent = true)
        state.append(1, memory(1), pointer(1), modelObjectPresent = false)

        val inputs = state.inputsForNextFrame()

        assertEquals(listOf(0, 77), pointerMarkers(inputs.objectPointerValues))
    }

    @Test
    fun bfloatRoundingUsesRoundToNearestEven() {
        val values = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x3f80_7fff)
            .putInt(0x3f80_8000.toInt())
            .putInt(0x3f81_8000)
            .array()

        val rounded = AndroidEdgeTamTemporalState.roundFloat32ToBfloat16(values)
        val buffer = ByteBuffer.wrap(rounded).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(0x3f80_0000, buffer.int)
        assertEquals(0x3f80_0000, buffer.int)
        assertEquals(0x3f82_0000, buffer.int)
    }

    @Test
    fun appendMustFollowDynamicSequenceOrder() {
        val state = AndroidEdgeTamTemporalState(pointer(99))
        assertThrows(IllegalArgumentException::class.java) {
            state.append(1, memory(1), pointer(1), modelObjectPresent = true)
        }
    }

    @Test
    fun snapshotLetsForwardAndBackwardBranchesEvolveIndependently() {
        val forward = AndroidEdgeTamTemporalState(pointer(99))
        forward.append(0, memory(0), pointer(0), modelObjectPresent = true)
        val backward = forward.snapshotCopy()

        forward.append(1, memory(1), pointer(1), modelObjectPresent = true)
        backward.append(1, memory(2), pointer(2), modelObjectPresent = true)

        assertEquals(listOf(0, 1), pointerMarkers(forward.inputsForNextFrame().objectPointerValues))
        assertEquals(listOf(0, 2), pointerMarkers(backward.inputsForNextFrame().objectPointerValues))
    }

    private fun memory(marker: Int): ByteArray = ByteArray(
        AndroidEdgeTamTemporalState.MASK_MEMORY_BYTES
    ).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(marker.toFloat()) }

    private fun pointer(marker: Int): ByteArray = ByteArray(
        AndroidEdgeTamTemporalState.OBJECT_POINTER_BYTES
    ).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(marker.toFloat()) }

    private fun memoryMarkers(bytes: ByteArray): List<Int> = bytes.asListOfMarkers(
        AndroidEdgeTamTemporalState.MASK_MEMORY_BYTES
    )

    private fun pointerMarkers(bytes: ByteArray): List<Int> = bytes.asListOfMarkers(
        AndroidEdgeTamTemporalState.OBJECT_POINTER_BYTES
    )

    private fun ByteArray.asListOfMarkers(stride: Int): List<Int> {
        require(size % stride == 0)
        return (0 until size / stride).map { index ->
            ByteBuffer.wrap(this, index * stride, Float.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .float.toInt()
        }
    }
}
