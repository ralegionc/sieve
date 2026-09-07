package dev.sieve.filter

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Arrays

/**
 * An immutable set of blocked domains, stored as a sorted array of 64-bit hashes.
 *
 * A [HashSet] of 150k domain strings costs tens of megabytes and is held for the entire life of
 * the VPN service. Hashing to a sorted `LongArray` costs 8 bytes per entry — about 1.2 MB for the
 * default lists — and a lookup is a binary search over a primitive array with no allocation. The
 * queried name is already known to the caller, so nothing is lost by not storing the strings.
 *
 * Collisions would produce a false block. At 64 bits and 10^6 entries the odds are around 1 in
 * 10^7, and the allowlist is checked first, so a user who ever hits one can undo it.
 */
class DomainSet private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    /**
     * True if [domain] or any of its parent domains is blocked, so that blocking `example.com`
     * also blocks `cdn.example.com`. The final label is never tested on its own — a list entry of
     * `com` must not take out the entire TLD.
     */
    fun matches(domain: String): Boolean {
        if (hashes.isEmpty() || domain.isEmpty()) return false
        var start = 0
        while (true) {
            if (contains(hash(domain, start, domain.length))) return true
            val dot = domain.indexOf('.', start)
            if (dot < 0) break
            start = dot + 1
            if (domain.indexOf('.', start) < 0) break // would leave a bare public suffix
        }
        return false
    }

    private fun contains(key: Long): Boolean = Arrays.binarySearch(hashes, key) >= 0

    fun writeTo(file: File) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(temporary.outputStream())).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT_VERSION)
            out.writeInt(hashes.size)
            for (value in hashes) out.writeLong(value)
        }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    companion object {
        private const val MAGIC = 0x53494556 // "SIEV"
        private const val FORMAT_VERSION = 1

        // FNV-1a, 64-bit.
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L

        val EMPTY = DomainSet(LongArray(0))

        fun of(domains: Collection<String>): DomainSet {
            val builder = Builder(domains.size)
            domains.forEach(builder::add)
            return builder.build()
        }

        fun readFrom(file: File): DomainSet? {
            if (!file.isFile) return null
            return runCatching {
                DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                    if (input.readInt() != MAGIC) return null
                    if (input.readInt() != FORMAT_VERSION) return null
                    val count = input.readInt()
                    if (count < 0) return null
                    val hashes = LongArray(count)
                    for (i in 0 until count) hashes[i] = input.readLong()
                    DomainSet(hashes)
                }
            }.getOrNull()
        }

        /** Case-folding FNV-1a over the ASCII range, hashing `[from, to)` of [value] in place. */
        fun hash(value: String, from: Int = 0, to: Int = value.length): Long {
            var result = FNV_OFFSET_BASIS
            for (i in from until to) {
                var code = value[i].code
                if (code in 'A'.code..'Z'.code) code += 32
                result = result xor (code.toLong() and 0xFF)
                result *= FNV_PRIME
            }
            return result
        }
    }

    /** Accumulates hashes without boxing, then sorts and de-duplicates once at the end. */
    class Builder(initialCapacity: Int = 1 shl 16) {
        private var values = LongArray(initialCapacity.coerceAtLeast(16))
        private var count = 0

        val size: Int get() = count

        fun add(domain: String) {
            if (count == values.size) values = values.copyOf(values.size * 2)
            values[count++] = hash(domain)
        }

        fun build(): DomainSet {
            if (count == 0) return EMPTY
            val sorted = values.copyOf(count)
            sorted.sort()
            var unique = 1
            for (i in 1 until sorted.size) {
                if (sorted[i] != sorted[unique - 1]) sorted[unique++] = sorted[i]
            }
            return DomainSet(sorted.copyOf(unique))
        }
    }
}
