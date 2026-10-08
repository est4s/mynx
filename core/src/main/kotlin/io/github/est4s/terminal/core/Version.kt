package io.github.est4s.terminal.core

/** An app version, `X.Y.Z` or `X.Y.Z-suffix` (a pre-release), ordered as semver orders them. */
data class Version(val major: Int, val minor: Int, val patch: Int, val suffix: String? = null) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch).let { if (it != 0) return it }
        return when {
            suffix == other.suffix -> 0
            suffix == null -> 1
            other.suffix == null -> -1
            else -> comparePreRelease(suffix.split('.'), other.suffix.split('.'))
        }
    }

    override fun toString() = "$major.$minor.$patch" + (suffix?.let { "-$it" } ?: "")
}

private val VERSION = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""")

/** A version name (`0.1.0`) or release tag (`v0.1.0-rc.1`); null if it's neither. */
fun parseVersion(text: String): Version? {
    val match = VERSION.matchEntire(text) ?: return null
    val (major, minor, patch) = match.groupValues.drop(1).take(3).map { it.toIntOrNull() ?: return null }
    return Version(major, minor, patch, match.groups[4]?.value)
}

/** Whether [candidate] is a later version than [current]; never when either doesn't parse. */
fun isNewer(candidate: String, current: String): Boolean {
    val new = parseVersion(candidate) ?: return false
    val now = parseVersion(current) ?: return false
    return new > now
}

// Semver: numeric parts compare as numbers and sort before words; more parts win a tie.
private fun comparePreRelease(a: List<String>, b: List<String>): Int {
    for ((x, y) in a.zip(b)) {
        val nx = x.takeIf { it.all(Char::isDigit) }?.toBigInteger()
        val ny = y.takeIf { it.all(Char::isDigit) }?.toBigInteger()
        val order = when {
            nx != null && ny != null -> nx.compareTo(ny)
            nx != null -> -1
            ny != null -> 1
            else -> x.compareTo(y)
        }
        if (order != 0) return order
    }
    return a.size.compareTo(b.size)
}
