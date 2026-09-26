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
}
