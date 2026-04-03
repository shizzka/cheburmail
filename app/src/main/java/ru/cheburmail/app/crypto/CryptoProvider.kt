package ru.cheburmail.app.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Singleton provider for LazySodiumAndroid instance.
 * Use this on Android; for JVM tests use TestCryptoProvider.
 */
object CryptoProvider {

    val lazySodium: LazySodiumAndroid by lazy {
        LazySodiumAndroid(SodiumAndroid())
    }
}
