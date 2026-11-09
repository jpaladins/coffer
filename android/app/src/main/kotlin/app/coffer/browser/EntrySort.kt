// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.browser

import app.coffer.data.Entry
import app.coffer.settings.SortKey
import app.coffer.settings.SortOrder
import java.text.Normalizer

private val byName = compareBy(String.CASE_INSENSITIVE_ORDER) { e: Entry -> e.name }

/** Folders always first; a missing date sorts last in both directions; ties by name. */
fun sortEntries(entries: List<Entry>, order: SortOrder): List<Entry> {
    val key: Comparator<Entry> = when (order.key) {
        SortKey.Name -> byName
        SortKey.Size -> compareBy { it.size }
        SortKey.Modified -> compareBy { it.modified }
    }
    val directed = if (order.ascending) key else key.reversed()
    val dated: Comparator<Entry> = if (order.key == SortKey.Modified) compareBy { it.modified == null } else compareBy { 0 }
    return entries.sortedWith(compareBy<Entry> { !it.isDir }.then(dated).then(directed).then(byName))
}

private val marks = Regex("\\p{M}+")

/** Lowercase without accents, so "perche" finds "Perché". */
fun foldForSearch(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

fun filterEntries(entries: List<Entry>, query: String): List<Entry> {
    val q = foldForSearch(query.trim())
    if (q.isEmpty()) return entries
    return entries.filter { q in foldForSearch(it.name) }
}
