// SPDX-License-Identifier: GPL-3.0-or-later
package org.rescuecodec.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.rescuecodec.app.R
import org.rescuecodec.codec.TextDomain

/**
 * Names of the team vocabularies (spec section 3.8) in the app's language. The codec knows the English names
 * ([TextDomain.Vocab.label], [TextDomain.Vocab.short]); a domain the app has no string for falls back to them.
 */
object VocabLabels {
    /** Long name of a team vocabulary setting for Settings: "Auto (default)", "General", "Search & Rescue", ... */
    @StringRes
    fun settingRes(setting: String): Int? = when (setting) {
        TextDomain.AUTO -> R.string.vocab_auto
        TextDomain.GENERAL -> R.string.vocab_general
        "emergency" -> R.string.vocab_emergency
        "sar" -> R.string.vocab_sar
        "fire" -> R.string.vocab_fire
        "hiking" -> R.string.vocab_hiking
        "medical" -> R.string.vocab_medical
        else -> null
    }

    /** Short name of a domain for tags and the byte line: "S&R", "Medical", ... */
    @StringRes
    fun shortRes(domain: String): Int? = when (domain) {
        "emergency" -> R.string.vocab_short_emergency
        "sar" -> R.string.vocab_short_sar
        "fire" -> R.string.vocab_short_fire
        "hiking" -> R.string.vocab_short_hiking
        "medical" -> R.string.vocab_short_medical
        else -> null
    }

    @Composable
    fun setting(setting: String): String =
        settingRes(setting)?.let { stringResource(it) } ?: TextDomain.CURRENT[setting]?.let { TextDomain.VOCABS.getValue(it).label } ?: setting

    @Composable
    fun short(domain: String): String =
        shortRes(domain)?.let { stringResource(it) } ?: TextDomain.CURRENT[domain]?.let { TextDomain.VOCABS.getValue(it).short } ?: domain
}
