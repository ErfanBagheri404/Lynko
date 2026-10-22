package com.lynko.app

/**
 * What the phone knows about the linked desktop.
 *
 * The desktop announces itself with a `hello` command on link-up; without it
 * the share-sheet picker can only show the socket's IP address, which tells
 * the user nothing about which PC they are about to send to.
 */
object PeerInfo {
    /** Desktop's computer name, or empty until `hello` arrives. */
    @Volatile var alias: String = ""

    /** Desktop name normalised to a filename-safe key. LocalSend keys a
     *  favorite by fingerprint; ours is the link's device id, which the phone
     *  never sees, so the hello alias is the closest stable identity. */
    fun keyOf(name: String): String =
        name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "desktop" }
}
