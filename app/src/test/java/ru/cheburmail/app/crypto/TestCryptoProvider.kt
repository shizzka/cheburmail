package ru.cheburmail.app.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/**
 * LazySodiumJava provider for JVM unit tests (no Android runtime needed).
 */
object TestCryptoProvider {

    val lazySodium: LazySodiumJava by lazy {
        LazySodiumJava(SodiumJava())
    }
}
