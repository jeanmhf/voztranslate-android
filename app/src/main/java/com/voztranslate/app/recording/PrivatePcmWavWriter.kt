package com.voztranslate.app.recording

import com.voztranslate.app.domain.AudioFrame
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

enum class PcmPayloadPresence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

/** Writes mono PCM16 frames to a private WAV file and refreshes its header as audio arrives. */
class PrivatePcmWavWriter private constructor(
    override val file: File,
    private val output: RandomAccessFile,
) : PcmAudioWriter {
    private var dataBytes = 0L
    private var nextSequenceNumber = 0L
    private var closed = false

    @Throws(IOException::class)
    override fun append(frame: AudioFrame) {
        check(!closed) { "El archivo de audio ya está cerrado." }
        if (frame.sequenceNumber != nextSequenceNumber) {
            throw IOException(
                "Se esperaba el frame PCM $nextSequenceNumber y se recibió ${frame.sequenceNumber}.",
            )
        }
        if (frame.sampleRateHz != SAMPLE_RATE_HZ) {
            throw IOException("La grabación requiere PCM mono a ${SAMPLE_RATE_HZ} Hz.")
        }

        val frameBytes = frame.samples.size.toLong() * BYTES_PER_SAMPLE
        if (dataBytes + frameBytes > MAX_WAV_DATA_BYTES) {
            throw IOException("La grabación alcanzó el tamaño máximo del formato WAV.")
        }
        val bytes = ByteArray(frame.samples.size * BYTES_PER_SAMPLE)
        frame.samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            bytes[index * 2] = value.toByte()
            bytes[index * 2 + 1] = (value shr 8).toByte()
        }

        output.seek(HEADER_SIZE.toLong() + dataBytes)
        output.write(bytes)
        dataBytes += frameBytes
        // Publish a valid, playable prefix after every delivered frame. If the process dies,
        // recovery can repair a torn header and retain the bytes already written.
        writeHeader(output, dataBytes)
        nextSequenceNumber += 1
    }

    @Throws(IOException::class)
    override fun finish() {
        if (closed) return
        writeHeader(output, dataBytes)
        output.fd.sync()
        closed = true
        output.close()
    }

    /** Finalizes the complete PCM prefix after a later append or storage failure. */
    override fun finishPreservingPrefix() {
        if (closed) return
        var failure: Throwable? = null
        try {
            output.setLength(HEADER_SIZE.toLong() + dataBytes)
            writeHeader(output, dataBytes)
            output.fd.sync()
        } catch (error: Throwable) {
            failure = error
        }
        try {
            output.close()
        } catch (closeError: Throwable) {
            val priorFailure = failure
            if (priorFailure == null) failure = closeError else priorFailure.addSuppressed(closeError)
        } finally {
            closed = true
        }
        when (val error = failure) {
            null -> Unit
            is IOException -> throw error
            else -> throw IOException("No se pudo cerrar el prefijo de audio guardado.", error)
        }
    }

    /** Closes and removes an unusable recording after a write/storage failure. */
    @Throws(IOException::class)
    override fun abortAndDelete() {
        if (!closed) {
            closed = true
            runCatching { output.close() }
        }
        if (file.exists() && !file.delete()) {
            throw IOException("No se pudo eliminar la grabación incompleta: ${file.name}.")
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val HEADER_SIZE = 44
        private const val MAX_WAV_DATA_BYTES = 0xFFFF_FFFFL - 36L

        @Throws(IOException::class)
        fun create(recordingsDirectory: File, sessionId: String): PrivatePcmWavWriter {
            if (!sessionId.matches(Regex("[A-Za-z0-9_-]+"))) {
                throw IOException("El identificador de sesión no es válido para un archivo local.")
            }
            if (!recordingsDirectory.exists() && !recordingsDirectory.mkdirs()) {
                throw IOException("No se pudo crear la carpeta privada de grabaciones.")
            }
            if (!recordingsDirectory.isDirectory) {
                throw IOException("La ubicación privada de grabaciones no está disponible.")
            }

            val file = File(recordingsDirectory, "$sessionId.wav")
            if (!file.createNewFile()) {
                throw IOException("Ya existe el archivo de audio de esta sesión.")
            }
            var output: RandomAccessFile? = null
            try {
                val openedOutput = RandomAccessFile(file, "rw")
                output = openedOutput
                openedOutput.setLength(0)
                writeHeader(openedOutput, 0)
                return PrivatePcmWavWriter(file, openedOutput)
            } catch (error: Throwable) {
                runCatching { output?.close() }
                runCatching { file.delete() }
                if (error is IOException) throw error
                throw IOException("No se pudo abrir el archivo privado de audio.", error)
            }
        }

        /** Attempts to repair an in-progress WAV; false does not prove that no PCM bytes exist. */
        fun recoverInterruptedFile(file: File): Boolean {
            if (!file.isFile || file.length() < HEADER_SIZE) return false
            return runCatching {
                RandomAccessFile(file, "rw").use { input ->
                    if (readAscii(input, 0, 4) != "RIFF" ||
                        readAscii(input, 8, 4) != "WAVE" ||
                        readAscii(input, 12, 4) != "fmt " ||
                        readAscii(input, 36, 4) != "data"
                    ) return false

                    input.seek(20)
                    val format = readUnsignedShort(input)
                    val channels = readUnsignedShort(input)
                    val sampleRate = readUnsignedInt(input)
                    input.seek(34)
                    val bitsPerSample = readUnsignedShort(input)
                    if (format != PCM_FORMAT || channels != CHANNELS || sampleRate != SAMPLE_RATE_HZ.toLong() ||
                        bitsPerSample != BITS_PER_SAMPLE
                    ) return false

                    val actualDataBytes = ((input.length() - HEADER_SIZE).coerceAtMost(MAX_WAV_DATA_BYTES) / 2) * 2
                    input.setLength(HEADER_SIZE.toLong() + actualDataBytes)
                    writeHeader(input, actualDataBytes)
                    // The file is structurally playable after the header rewrite. A sync error
                    // should not discard a valid prefix during recovery from a storage failure.
                    runCatching { input.fd.sync() }
                    true
                }
            }.getOrDefault(false)
        }

        /** Returns UNKNOWN when the file cannot be inspected; callers must not delete then. */
        fun inspectPcmPayload(file: File): PcmPayloadPresence =
            inspectPcmPayload(file) { candidate -> Files.notExists(candidate.toPath()) }

        /** Probe overload for deterministic handling of an unopenable file. */
        internal fun inspectPcmPayload(
            file: File,
            confirmsAbsence: (File) -> Boolean,
        ): PcmPayloadPresence {
            return try {
                RandomAccessFile(file, "r").use { input ->
                    if (input.length() > HEADER_SIZE) PcmPayloadPresence.PRESENT else PcmPayloadPresence.ABSENT
                }
            } catch (_: FileNotFoundException) {
                if (runCatching { confirmsAbsence(file) }.getOrDefault(false)) {
                    PcmPayloadPresence.ABSENT
                } else {
                    PcmPayloadPresence.UNKNOWN
                }
            } catch (_: Exception) {
                PcmPayloadPresence.UNKNOWN
            }
        }

        private const val PCM_FORMAT = 1

        private fun writeHeader(output: RandomAccessFile, sampleBytes: Long) {
            val oldPosition = output.filePointer
            output.seek(0)
            writeAscii(output, "RIFF")
            writeLittleEndianInt(output, (36L + sampleBytes).toInt())
            writeAscii(output, "WAVE")
            writeAscii(output, "fmt ")
            writeLittleEndianInt(output, 16)
            writeLittleEndianShort(output, PCM_FORMAT)
            writeLittleEndianShort(output, CHANNELS)
            writeLittleEndianInt(output, SAMPLE_RATE_HZ)
            writeLittleEndianInt(output, SAMPLE_RATE_HZ * CHANNELS * BYTES_PER_SAMPLE)
            writeLittleEndianShort(output, CHANNELS * BYTES_PER_SAMPLE)
            writeLittleEndianShort(output, BITS_PER_SAMPLE)
            writeAscii(output, "data")
            writeLittleEndianInt(output, sampleBytes.toInt())
            output.seek(maxOf(oldPosition, HEADER_SIZE.toLong()))
        }

        private fun writeLittleEndianShort(output: RandomAccessFile, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        private fun writeLittleEndianInt(output: RandomAccessFile, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
            output.write((value ushr 16) and 0xFF)
            output.write((value ushr 24) and 0xFF)
        }

        private fun readAscii(input: RandomAccessFile, offset: Long, length: Int): String {
            val bytes = ByteArray(length)
            input.seek(offset)
            input.readFully(bytes)
            return String(bytes, Charsets.US_ASCII)
        }

        private fun readUnsignedShort(input: RandomAccessFile): Int =
            input.readUnsignedByte() or (input.readUnsignedByte() shl 8)

        private fun readUnsignedInt(input: RandomAccessFile): Long =
            input.readUnsignedByte().toLong() or
                (input.readUnsignedByte().toLong() shl 8) or
                (input.readUnsignedByte().toLong() shl 16) or
                (input.readUnsignedByte().toLong() shl 24)

        private fun writeAscii(output: RandomAccessFile, value: String) {
            output.write(value.toByteArray(Charsets.US_ASCII))
        }
    }
}
