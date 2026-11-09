// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer

import app.coffer.browser.filterEntries
import app.coffer.browser.sortEntries
import app.coffer.data.Entry
import app.coffer.settings.SortKey
import app.coffer.settings.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class EntrySortTest {
    private val entries = listOf(
        Entry("b.txt", false, 10, 300),
        Entry("Zeta", true, 0, 100),
        Entry("A.jpg", false, 500, null),
        Entry("alpha", true, 0, 200),
        Entry("c.bin", false, 50, 100),
    )

    private fun names(l: List<Entry>) = l.map { it.name }

    @Test fun folders_first_then_name_case_insensitive() =
        assertEquals(listOf("alpha", "Zeta", "A.jpg", "b.txt", "c.bin"), names(sortEntries(entries, SortOrder())))

    @Test fun name_descending_keeps_folders_first() =
        assertEquals(listOf("Zeta", "alpha", "c.bin", "b.txt", "A.jpg"), names(sortEntries(entries, SortOrder(SortKey.Name, false))))

    @Test fun size_descending_keeps_folders_first() =
        assertEquals(listOf("alpha", "Zeta", "A.jpg", "c.bin", "b.txt"), names(sortEntries(entries, SortOrder(SortKey.Size, false))))

    @Test fun modified_null_sorts_last() {
        assertEquals(listOf("Zeta", "alpha", "c.bin", "b.txt", "A.jpg"), names(sortEntries(entries, SortOrder(SortKey.Modified, true))))
        assertEquals(listOf("alpha", "Zeta", "b.txt", "c.bin", "A.jpg"), names(sortEntries(entries, SortOrder(SortKey.Modified, false))))
    }

    @Test fun search_ignores_case_and_accents() {
        val l = listOf(Entry("Perché.txt", false, 1, null), Entry("Note.txt", false, 1, null))
        assertEquals(listOf("Perché.txt"), names(filterEntries(l, "perche")))
        assertEquals(listOf("Perché.txt"), names(filterEntries(l, "PERCHÉ")))
    }

    @Test fun empty_query_returns_all() = assertEquals(entries, filterEntries(entries, "  "))
}
