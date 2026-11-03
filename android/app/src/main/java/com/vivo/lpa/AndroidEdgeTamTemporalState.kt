package com.vivo.lpa

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AndroidEdgeTamTemporalInputs(
    val spatialMemoryCount: Int,
    val objectPointerCount: Int,
    val contextFileName: String,
    val maskMemoryValues: ByteArray,
    val objectPointerValues: ByteArray
)

/** Host-owned EdgeTAM memory/pointer bank. Counts are derived from processed frames. */
class AndroidEdgeTamTemporalState(
    private val noObjectPointer: ByteArray
) {
    private data class Entry(
        val sequenceIndex: Int,
        val maskMemory: ByteArray,
        val objectPointer: ByteArray
    )

    private var condition: Entry? = null
    private val nonCondition = ArrayDeque<Entry>()
    private var totalProcessedFrames = 0

    init {
        require(noObjectPointer.size == OBJECT_POINTER_BYTES)
    }

    val processedFrameCount: Int get() = totalProcessedFrames
    val hasCondition: Boolean get() = condition != null

    fun append(
        sequenceIndex: Int,
        maskMemoryFloat32: ByteArray,
        objectPointerFloat32: ByteArray,
        modelObjectPresent: Boolean
    ) {
        require(sequenceIndex == processedFrameCount) {
            "temporal state must be appended in contiguous sequence order"
        }
        require(maskMemoryFloat32.size == MASK_MEMORY_BYTES)
        require(objectPointerFloat32.size == OBJECT_POINTER_BYTES)
        val pointer = if (modelObjectPresent) objectPointerFloat32.copyOf()
            else noObjectPointer.copyOf()
        val entry = Entry(
            sequenceIndex,
            roundFloat32ToBfloat16(maskMemoryFloat32),
            pointer
        )
        if (sequenceIndex == 0) {
            require(condition == null)
            condition = entry
        } else {
            require(condition != null)
            nonCondition.addLast(entry)
            while (nonCondition.size > MAX_NON_CONDITION_POINTERS) nonCondition.removeFirst()
        }
        totalProcessedFrames += 1
    }

    fun inputsForNextFrame(): AndroidEdgeTamTemporalInputs {
        val conditionEntry = requireNotNull(condition) { "conditioning state is missing" }
        val memoryTail = nonCondition.toList().takeLast(MAX_NON_CONDITION_MEMORIES)
        val memories = listOf(conditionEntry) + memoryTail
        val pointerTail = nonCondition.toList().takeLast(MAX_NON_CONDITION_POINTERS).asReversed()
        val pointers = listOf(conditionEntry) + pointerTail
        require(memories.size in 1..MAX_SPATIAL_MEMORIES)
        require(pointers.size in 1..MAX_OBJECT_POINTERS)
        val contextName = if (memories.size < MAX_SPATIAL_MEMORIES) {
            require(pointers.size == memories.size)
            "memory_attention_${memories.size}_o3.bin"
        } else if (pointers.size == MAX_SPATIAL_MEMORIES) {
            "memory_attention_7_o3.bin"
        } else {
            "memory_attention_7_${pointers.size}_o3.bin"
        }
        return AndroidEdgeTamTemporalInputs(
            spatialMemoryCount = memories.size,
            objectPointerCount = pointers.size,
            contextFileName = contextName,
            maskMemoryValues = concatenate(memories.map { it.maskMemory }),
            objectPointerValues = concatenate(pointers.map { it.objectPointer })
        )
    }

    fun snapshotCopy(): AndroidEdgeTamTemporalState {
        val copy = AndroidEdgeTamTemporalState(noObjectPointer.copyOf())
        copy.condition = condition?.let {
            Entry(it.sequenceIndex, it.maskMemory.copyOf(), it.objectPointer.copyOf())
        }
        nonCondition.forEach { entry ->
            copy.nonCondition.addLast(Entry(
                entry.sequenceIndex,
                entry.maskMemory.copyOf(),
                entry.objectPointer.copyOf()
            ))
        }
        copy.totalProcessedFrames = totalProcessedFrames
        return copy
    }

    companion object {
        const val MAX_SPATIAL_MEMORIES = 7
        const val MAX_OBJECT_POINTERS = 16
        const val MAX_NON_CONDITION_MEMORIES = MAX_SPATIAL_MEMORIES - 1
        const val MAX_NON_CONDITION_POINTERS = MAX_OBJECT_POINTERS - 1
        const val MASK_MEMORY_FLOAT_COUNT = 512 * 64
        const val MASK_MEMORY_BYTES = MASK_MEMORY_FLOAT_COUNT * Float.SIZE_BYTES
        const val OBJECT_POINTER_FLOAT_COUNT = 256
        const val OBJECT_POINTER_BYTES = OBJECT_POINTER_FLOAT_COUNT * Float.SIZE_BYTES

        fun loadNpyFloat32(file: File, expectedFloatCount: Int): ByteArray {
            require(file.isFile && expectedFloatCount > 0)
            val bytes = file.readBytes()
            require(bytes.size > 16 && bytes.copyOfRange(0, 6).contentEquals(
                byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(),
                    'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
            )) { "invalid NPY magic" }
            val major = bytes[6].toInt() and 0xff
            val headerLengthBytes = if (major == 1) 2 else 4
            require(major in 1..3 && bytes.size >= 8 + headerLengthBytes)
            val headerLength = ByteBuffer.wrap(bytes, 8, headerLengthBytes)
                .order(ByteOrder.LITTLE_ENDIAN)
                .let { if (headerLengthBytes == 2) it.short.toInt() and 0xffff else it.int }
            val dataOffset = 8 + headerLengthBytes + headerLength
            val expectedBytes = expectedFloatCount * Float.SIZE_BYTES
            require(dataOffset >= 0 && dataOffset + expectedBytes == bytes.size) {
                "unexpected NPY float payload size"
            }
            val header = bytes.copyOfRange(8 + headerLengthBytes, dataOffset).toString(Charsets.US_ASCII)
            require("'descr': '<f4'" in header || "\"descr\": \"<f4\"" in header) {
                "NPY tensor must be little-endian float32"
            }
            require("'fortran_order': False" in header || "\"fortran_order\": false" in header) {
                "Fortran-order NPY tensors are unsupported"
            }
            return bytes.copyOfRange(dataOffset, bytes.size)
        }

        internal fun roundFloat32ToBfloat16(values: ByteArray): ByteArray {
            require(values.size % Float.SIZE_BYTES == 0)
            val output = values.copyOf()
            val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
            for (index in 0 until output.size / Float.SIZE_BYTES) {
                val offset = index * Float.SIZE_BYTES
                val bits = buffer.getInt(offset)
                val rounded = bits + 0x7fff + ((bits ushr 16) and 1)
                buffer.putInt(offset, rounded and -0x10000)
            }
            return output
        }

        private fun concatenate(values: List<ByteArray>): ByteArray {
            val total = values.sumOf { it.size }
            val output = ByteArray(total)
            var offset = 0
            values.forEach { value ->
                value.copyInto(output, offset)
                offset += value.size
            }
            return output
        }
    }
}
