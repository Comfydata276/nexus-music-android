package org.medianexus.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.medianexus.music.store.SettingsDamage

/** Something the app needs to tell the person, shown until they dismiss it. The words are in
 *  the string resources; this says which. */
sealed interface Notice {
    data class SettingsDamaged(val damage: SettingsDamage) : Notice

    /** Signed in, but the password couldn't be kept: the next start asks for it again. */
    data object PasswordNotSaved : Notice

    /** Signed in over plain HTTP to somewhere beyond the home network. */
    data object SignedInWithoutEncryption : Notice
}

/** The notices waiting to be seen, oldest first. */
class Notices {
    private val waiting = MutableStateFlow<List<Notice>>(emptyList())
    val state: StateFlow<List<Notice>> = waiting.asStateFlow()

    fun post(notice: Notice) = waiting.update { if (notice in it) it else it + notice }

    fun dismiss(notice: Notice) = waiting.update { it - notice }
}
