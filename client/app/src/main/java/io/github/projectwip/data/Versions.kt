package io.github.projectwip.data

/** Comparing version names like "6" or "0.4.2-preview" and tags like "v6.0" or "v0.4.2-preview". Pure, so it is unit-tested. */
object Versions {
    /**
     * This build is long-term support: it may keep being played after newer builds come out. The update screen
     * can be put off (it comes back at every start), and the server keeps a referee for it (see `referee.py`).
     * Set this to false only for a build that must be replaced.
     */
    const val LTS = true

    /** The numeric parts (major, minor, patch), or null if [name] doesn't start with a version. */
    fun parse(name: String): IntArray? {
        val m = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(name.trim()) ?: return null
        return intArrayOf(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].toIntOrNull() ?: 0)
    }

    /** True if [candidate] is a later version than [current]. Unreadable versions are never "newer". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
