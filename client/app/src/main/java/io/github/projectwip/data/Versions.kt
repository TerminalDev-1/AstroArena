package io.github.projectwip.data

/** Comparing version names like "0.4.2-preview" or tags like "v0.4.2-preview". Pure, so it is unit-tested. */
object Versions {
    /** The numeric parts (major, minor, patch), or null if [name] doesn't start with a version. */
    fun parse(name: String): IntArray? {
        val m = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?""").find(name.trim()) ?: return null
        return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull() ?: 0)
    }

    /** True if [candidate] is a later version than [current]. Unreadable versions are never "newer". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
