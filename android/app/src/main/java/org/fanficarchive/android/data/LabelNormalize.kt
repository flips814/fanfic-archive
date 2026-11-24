package org.fanficarchive.android.data

private val slashRe = Regex("""\s*[\\/]\s*""")
private val ampRe = Regex("""\s*&\s*""")
private val spaceRe = Regex("""\s+""")

/** Match server-side label collapsing for filters (case / slash / spacing variants). */
fun normalizeLabelKey(name: String): String {
    var s = name.trim().lowercase()
    s = slashRe.replace(s, "/")
    s = ampRe.replace(s, " & ")
    return spaceRe.replace(s, " ").trim()
}

fun labelsMatch(selected: String, candidates: List<String>): Boolean {
    val key = normalizeLabelKey(selected)
    if (key.isEmpty()) return false
    return candidates.any { normalizeLabelKey(it) == key }
}
