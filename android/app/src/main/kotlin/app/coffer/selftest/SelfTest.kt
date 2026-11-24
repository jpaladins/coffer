// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.selftest

enum class CheckGroup { Crypto, Volume, Device }

/** Pass = verified; Warn = works, but a setting could be safer; Fail = something is broken. */
enum class CheckStatus { Pass, Warn, Fail }

/** One line of the security check. [id] keys the localized text; [arg] fills it when needed. */
data class CheckResult(
    val group: CheckGroup,
    val id: String,
    val status: CheckStatus,
    val arg: String? = null,
)

/** The worst status wins: one failure makes the whole check fail. */
fun overall(results: List<CheckResult>): CheckStatus = when {
    results.any { it.status == CheckStatus.Fail } -> CheckStatus.Fail
    results.any { it.status == CheckStatus.Warn } -> CheckStatus.Warn
    else -> CheckStatus.Pass
}

/**
 * Plain-text report for a bug report: app version, Android version and the
 * check outcomes only. No container names, paths or passwords.
 */
fun report(results: List<CheckResult>, appVersion: String, androidVersion: String, device: String): String =
    buildString {
        appendLine("Coffer $appVersion — security check")
        appendLine("Android $androidVersion, $device")
        appendLine("Result: ${overall(results)}")
        CheckGroup.entries.forEach { g ->
            val inGroup = results.filter { it.group == g }
            if (inGroup.isNotEmpty()) {
                appendLine()
                appendLine("[$g]")
                inGroup.forEach { r -> appendLine("${r.status.name.uppercase()}  ${r.id}${r.arg?.let { " ($it)" } ?: ""}") }
            }
        }
    }.trimEnd()
