package com.portal.tv.data

/**
 * A Portal profile. Android TV runs as a single OS user, so profiles are
 * app-level: each keeps its own usage history and settings (see
 * [com.portal.tv.repo.ProfileManager]).
 *
 * A profile is either a plain Portal profile or linked to a Google account,
 * in which case [email] and [photoFile] come from that account.
 */
data class Profile(
    val id: Int,
    val name: String,
    val colorIndex: Int = 0,
    val type: Type = Type.APP,
    val email: String? = null,
    /** File name (inside the profile photo directory) of the cached avatar, if any. */
    val photoFile: String? = null,
    /** Kid profiles only see [allowedApps], and can't open settings or manage profiles without the parent PIN. */
    val isKids: Boolean = false,
    /** Package names a kid profile may see and launch. Ignored for adult profiles. */
    val allowedApps: Set<String> = emptySet(),
    /** Built-in avatar chosen from the adult or kids set (see AvatarArt); unused when there is a Google photo. */
    val avatarId: String? = null
) {
    enum class Type { APP, GOOGLE }

    val isGoogle: Boolean get() = type == Type.GOOGLE
}
