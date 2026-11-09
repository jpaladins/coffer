// SPDX-License-Identifier: GPL-3.0-or-later
package app.coffer.onboarding

/** First run only: an update from 0.0.1a with containers already listed skips it. */
fun shouldShowOnboarding(done: Boolean, vaultCount: Int): Boolean = !done && vaultCount == 0

enum class OnboardingExit { Skip, AddExisting, CreateNew }
