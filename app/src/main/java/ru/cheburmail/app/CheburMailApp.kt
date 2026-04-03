package ru.cheburmail.app

import android.app.Application
import ru.cheburmail.app.storage.EncryptedDataStoreFactory

class CheburMailApp : Application() {

    override fun onCreate() {
        super.onCreate()
        EncryptedDataStoreFactory.initTink()
    }
}
