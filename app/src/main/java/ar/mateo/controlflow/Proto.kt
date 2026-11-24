package ar.mateo.controlflow

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** Minimal protobuf writer (only what the Android TV Remote v2 protocol needs). */
class PB {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt()); return
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun int(field: Int, v: Long): PB { varint((field shl 3).toLong()); varint(v); return this }
    fun int(field: Int, v: Int): PB = int(field, v.toLong())
    fun bytes(field: Int, b: ByteArray): PB {
        varint(((field shl 3) or 2).toLong()); varint(b.size.toLong()); out.write(b); return this
    }
    fun str(field: Int, s: String): PB = bytes(field, s.toByteArray(Charsets.UTF_8))
    fun msg(field: Int, m: PB): PB = bytes(field, m.toBytes())
    fun toBytes(): ByteArray = out.toByteArray()
}

/** Minimal protobuf reader: field number -> list of values (Long for varint/fixed, ByteArray for length-delimited). */
class PMsg private constructor(private val fields: Map<Int, List<Any>>) {
    fun has(f: Int) = fields.containsKey(f)
    fun int(f: Int): Long = fields[f]?.firstOrNull() as? Long ?: 0L
    fun bytes(f: Int): ByteArray? = fields[f]?.firstOrNull() as? ByteArray
    fun msg(f: Int): PMsg? = bytes(f)?.let { parse(it) }
    fun str(f: Int): String? = bytes(f)?.let { String(it, Charsets.UTF_8) }
    fun keys(): Set<Int> = fields.keys

    companion object {
        fun parse(b: ByteArray): PMsg {
            val map = LinkedHashMap<Int, MutableList<Any>>()
            var i = 0
            fun readVarint(): Long {
                var shift = 0; var result = 0L
                while (true) {
                    if (i >= b.size) throw IllegalArgumentException("varint truncado")
                    val x = b[i++].toInt() and 0xFF
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) return result
                    shift += 7
                }
            }
            while (i < b.size) {
                val key = readVarint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> readVarint()
                    1 -> { var r = 0L; for (k in 0 until 8) r = r or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; r }
                    2 -> { val len = readVarint().toInt(); val r = b.copyOfRange(i, i + len); i += len; r }
                    5 -> { var r = 0L; for (k in 0 until 4) r = r or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 4; r }
                    else -> throw IllegalArgumentException("wire type no soportado")
                }
                map.getOrPut(field) { mutableListOf() }.add(value)
            }
            return PMsg(map)
        }
    }
}

/** Messages are prefixed by their length as a varint. */
object Framing {
    fun write(os: OutputStream, payload: ByteArray) {
        val buf = ByteArrayOutputStream()
        var v = payload.size
        while (true) {
            if (v and 0x7F.inv() == 0) { buf.write(v); break }
            buf.write((v and 0x7F) or 0x80); v = v ushr 7
        }
        buf.write(payload)
        os.write(buf.toByteArray())
        os.flush()
    }

    fun read(ins: InputStream): ByteArray {
        var shift = 0; var len = 0
        while (true) {
            val x = ins.read()
            if (x < 0) throw EOFException("Conexión cerrada por el deco")
            len = len or ((x and 0x7F) shl shift)
            if (x and 0x80 == 0) break
            shift += 7
        }
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = ins.read(out, off, len - off)
            if (n < 0) throw EOFException("Conexión cerrada por el deco")
            off += n
        }
        return out
    }
}
