package com.example.agent.tactical

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Szenario-Kompression (docs/TACTICAL.md) — Portierung der v9.0-Kernidee
 * (ScenarioCompressor). Die Spec sah LZ4 vor; ohne externe Abhängigkeit
 * nutzt die App `Deflater`/`Inflater` (zlib) — identisch zum Python-Port
 * (`edge-agent/tactical.py` → zlib), sodass Szenarien geräteübergreifend
 * austauschbar sind.
 */
object ScenarioCompressor {

    /** Komprimiert Text (z. B. Szenario-/Export-JSON) nach zlib. */
    fun compress(text: String): ByteArray {
        val input = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(6)
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream(input.size.coerceAtLeast(64))
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            if (count > 0) output.write(buffer, 0, count)
        }
        deflater.end()
        return output.toByteArray()
    }

    /** Dekomprimiert zlib-Daten zurück zu Text. */
    fun decompress(data: ByteArray): String {
        val inflater = Inflater()
        inflater.setInput(data)
        val output = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0 && inflater.needsInput()) break
            output.write(buffer, 0, count)
        }
        inflater.end()
        return output.toByteArray().toString(Charsets.UTF_8)
    }
}
