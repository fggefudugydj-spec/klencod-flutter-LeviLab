package com.levilab

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Validation(val ok: Boolean, val reason: String)
class ElfInfo(val needed: List<String>, val jni: List<String>, val soname: String?)

object ElfUtil {
    const val MIN_SIZE = 1024L * 1024L

    fun isElfHeader(h: ByteArray): Boolean =
        h.size >= 4 && h[0] == 0x7F.toByte() && h[1] == 0x45.toByte() && h[2] == 0x4C.toByte() && h[3] == 0x46.toByte()

    fun validate(f: File, requireSize: Boolean = true): Validation {
        if (!f.isFile) return Validation(false, "file does not exist: ${f.path}")
        if (!f.canRead()) return Validation(false, "file is not readable: ${f.path}")
        val len = f.length()
        if (requireSize && len <= MIN_SIZE) return Validation(false, "size $len bytes is not larger than 1 MB")
        val h = ByteArray(4)
        try {
            RandomAccessFile(f, "r").use { r ->
                if (r.length() < 4) return Validation(false, "file shorter than 4 bytes")
                r.readFully(h)
            }
        } catch (t: Throwable) {
            return Validation(false, "read error: $t")
        }
        if (!isElfHeader(h)) return Validation(false, "header is not 7F 45 4C 46 (not an ELF file)")
        return Validation(true, "valid ELF header, $len bytes")
    }

    fun parse(f: File): ElfInfo {
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(64)
            r.readFully(h)
            require(isElfHeader(h)) { "not an ELF file" }
            require(h[4].toInt() == 2) { "not ELF64" }
            require(h[5].toInt() == 1) { "not little-endian" }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val phoff = b.getLong(0x20)
            val phentsize = b.getShort(0x36).toInt() and 0xFFFF
            val phnum = b.getShort(0x38).toInt() and 0xFFFF
            require(phentsize >= 56) { "bad phentsize $phentsize" }
            val loads = ArrayList<LongArray>()
            var dynOff = -1L
            var dynSz = 0L
            val ph = ByteArray(phentsize)
            for (i in 0 until phnum) {
                r.seek(phoff + i.toLong() * phentsize)
                r.readFully(ph)
                val p = ByteBuffer.wrap(ph).order(ByteOrder.LITTLE_ENDIAN)
                val type = p.getInt(0)
                if (type == 1) loads.add(longArrayOf(p.getLong(16), p.getLong(8), p.getLong(32)))
                if (type == 2) { dynOff = p.getLong(8); dynSz = p.getLong(32) }
            }
            require(dynOff >= 0) { "no PT_DYNAMIC segment" }
            require(dynSz in 16..(1L shl 20)) { "unreasonable PT_DYNAMIC size $dynSz" }
            val dyn = ByteArray(dynSz.toInt())
            r.seek(dynOff)
            r.readFully(dyn)
            val d = ByteBuffer.wrap(dyn).order(ByteOrder.LITTLE_ENDIAN)
            val neededOffs = ArrayList<Long>()
            var strtab = -1L
            var strsz = 0L
            var sonameOff = -1L
            var pos = 0
            while (pos + 16 <= dyn.size) {
                val tag = d.getLong(pos)
                val v = d.getLong(pos + 8)
                pos += 16
                when (tag) {
                    0L -> pos = dyn.size
                    1L -> neededOffs.add(v)
                    5L -> strtab = v
                    10L -> strsz = v
                    14L -> sonameOff = v
                }
            }
            require(strtab >= 0 && strsz > 0) { "no DT_STRTAB or DT_STRSZ" }
            require(strsz <= 64L * 1024 * 1024) { "dynstr too large: $strsz" }
            var strOff = -1L
            for (l in loads) if (strtab >= l[0] && strtab < l[0] + l[2]) { strOff = l[1] + (strtab - l[0]); break }
            require(strOff >= 0) { "DT_STRTAB is not inside any PT_LOAD" }
            val str = ByteArray(strsz.toInt())
            r.seek(strOff)
            r.readFully(str)
            fun at(o: Long): String {
                var e = o.toInt()
                while (e < str.size && str[e].toInt() != 0) e++
                return String(str, o.toInt(), e - o.toInt(), Charsets.UTF_8)
            }
            val needed = neededOffs.filter { it in 0 until strsz }.map { at(it) }
            val jni = ArrayList<String>()
            var s = 0
            while (s < str.size) {
                var e = s
                while (e < str.size && str[e].toInt() != 0) e++
                if (e > s) {
                    val n = String(str, s, e - s, Charsets.UTF_8)
                    if (n.startsWith("Java_") || n == "JNI_OnLoad" || n == "JNI_OnUnload") jni.add(n)
                }
                s = e + 1
            }
            return ElfInfo(needed, jni, if (sonameOff in 0 until strsz) at(sonameOff) else null)
        }
    }
}
