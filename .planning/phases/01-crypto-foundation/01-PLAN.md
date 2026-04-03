---
plan: 01
title: Инициализация Android-проекта
wave: 1
depends_on: []
autonomous: true
files_modified:
  - settings.gradle.kts
  - build.gradle.kts
  - gradle/libs.versions.toml
  - gradle/wrapper/gradle-wrapper.properties
  - app/build.gradle.kts
  - app/src/main/AndroidManifest.xml
  - app/src/main/java/ru/cheburmail/app/CheburMailApp.kt
  - app/src/main/java/ru/cheburmail/app/MainActivity.kt
  - app/src/main/java/ru/cheburmail/app/ui/theme/Theme.kt
  - app/src/main/java/ru/cheburmail/app/ui/theme/Color.kt
  - app/src/main/java/ru/cheburmail/app/ui/theme/Type.kt
  - app/src/main/res/values/strings.xml
  - app/src/main/res/values/themes.xml
  - app/proguard-rules.pro
  - .gitignore
---

# Инициализация Android-проекта

## Цель

Создать полностью рабочий Android-проект с Gradle 9.3.1, AGP 9.1.0, Kotlin 2.3.20, Jetpack Compose и всеми зависимостями фазы 1 (Lazysodium, Tink, DataStore). Проект должен компилироваться и запускаться на эмуляторе с пустым Compose-экраном.

## Задачи

<task id="1" name="Gradle wrapper и корневая конфигурация" type="feat">
Создать структуру Gradle-проекта:

1. `gradle/wrapper/gradle-wrapper.properties` — указать `distributionUrl` на Gradle 9.3.1:
   ```
   distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
   ```

2. `settings.gradle.kts` — корневой файл с pluginManagement и dependencyResolutionManagement:
   ```kotlin
   pluginManagement {
       repositories {
           google()
           mavenCentral()
           gradlePluginPortal()
       }
   }
   dependencyResolutionManagement {
       repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
       repositories {
           google()
           mavenCentral()
       }
   }
   rootProject.name = "CheburMail"
   include(":app")
   ```

3. `build.gradle.kts` (корень проекта):
   ```kotlin
   plugins {
       id("com.android.application") version "9.1.0" apply false
       id("com.google.devtools.ksp") version "2.3.20-1.0.31" apply false
   }
   ```

4. `gradle/libs.versions.toml` — version catalog со ВСЕМИ зависимостями фазы 1 (точные версии):
   - kotlin = "2.3.20"
   - agp = "9.1.0"
   - ksp = "2.3.20-1.0.31"
   - compose-bom = "2026.03.00"
   - activity-compose = "1.10.1"
   - lifecycle = "2.9.0"
   - navigation-compose = "2.9.0"
   - core-ktx = "1.16.0"
   - datastore = "1.2.1"
   - datastore-tink = "1.3.0-alpha07"
   - tink-android = "1.8.0"
   - lazysodium-android = "5.2.0"
   - jna = "5.14.0"
   - room = "2.8.4"
   - work-runtime = "2.11.1"
   - junit = "4.13.2"
   - androidx-test-ext = "1.2.1"
   - espresso = "3.6.1"
</task>

<task id="2" name="Модуль app — build.gradle.kts" type="feat">
Создать `app/build.gradle.kts` с полной конфигурацией:

```kotlin
plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ru.cheburmail.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.cheburmail.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}
```

Зависимости через version catalog (libs.versions.toml):
- Compose BOM 2026.03.00 + ui, ui-graphics, ui-tooling-preview, material3, material-icons-extended
- activity-compose, lifecycle-runtime-ktx, lifecycle-viewmodel-compose, navigation-compose, core-ktx
- Lazysodium-android 5.2.0 + JNA 5.14.0@aar
- DataStore 1.2.1 + datastore-tink 1.3.0-alpha07 + Tink Android 1.8.0
- Room 2.8.4 (runtime + ktx + compiler через KSP) — заглушка для будущих фаз
- WorkManager 2.11.1 — заглушка для будущих фаз
- Тестовые: junit 4.13.2, androidx-test-ext-junit, espresso-core, compose ui-test-junit4

ВАЖНО: JNA подключать строго с `@aar`:
```kotlin
implementation("net.java.dev.jna:jna:5.14.0@aar")
```
</task>

<task id="3" name="AndroidManifest.xml" type="feat">
Создать `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".CheburMailApp"
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.CheburMail"
        android:networkSecurityConfig="@xml/network_security_config">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.CheburMail">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Ключевые моменты:
- `android:allowBackup="false"` и `android:fullBackupContent="false"` — запрет бэкапа приватных ключей
- `android:networkSecurityConfig` — запрет cleartext-трафика
- `android:name=".CheburMailApp"` — кастомный Application-класс
</task>

<task id="4" name="Application-класс и MainActivity" type="feat">
1. Создать `app/src/main/java/ru/cheburmail/app/CheburMailApp.kt`:
   ```kotlin
   package ru.cheburmail.app

   import android.app.Application

   class CheburMailApp : Application() {
       override fun onCreate() {
           super.onCreate()
           // Инициализация Tink будет добавлена в плане 03
       }
   }
   ```

2. Создать `app/src/main/java/ru/cheburmail/app/MainActivity.kt`:
   ```kotlin
   package ru.cheburmail.app

   import android.os.Bundle
   import androidx.activity.ComponentActivity
   import androidx.activity.compose.setContent
   import androidx.activity.enableEdgeToEdge
   import androidx.compose.foundation.layout.fillMaxSize
   import androidx.compose.material3.MaterialTheme
   import androidx.compose.material3.Surface
   import androidx.compose.material3.Text
   import androidx.compose.ui.Modifier
   import ru.cheburmail.app.ui.theme.CheburMailTheme

   class MainActivity : ComponentActivity() {
       override fun onCreate(savedInstanceState: Bundle?) {
           super.onCreate(savedInstanceState)
           enableEdgeToEdge()
           setContent {
               CheburMailTheme {
                   Surface(
                       modifier = Modifier.fillMaxSize(),
                       color = MaterialTheme.colorScheme.background
                   ) {
                       Text("CheburMail")
                   }
               }
           }
       }
   }
   ```
</task>

<task id="5" name="Тема Compose и ресурсы" type="feat">
1. Создать `app/src/main/java/ru/cheburmail/app/ui/theme/Color.kt`:
   - Определить цветовую палитру Material 3 (светлая и тёмная тема)
   - Основной цвет: синий/индиго (мессенджер-стиль)

2. Создать `app/src/main/java/ru/cheburmail/app/ui/theme/Type.kt`:
   - Типографика Material 3

3. Создать `app/src/main/java/ru/cheburmail/app/ui/theme/Theme.kt`:
   - `@Composable fun CheburMailTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
   - Использовать `dynamicColorScheme` на Android 12+ с fallback на статические цвета

4. Создать `app/src/main/res/values/strings.xml`:
   ```xml
   <string name="app_name">CheburMail</string>
   ```

5. Создать `app/src/main/res/values/themes.xml` — тема для splash/стартового экрана

6. Создать `app/src/main/res/xml/network_security_config.xml`:
   ```xml
   <network-security-config>
       <base-config cleartextTrafficPermitted="false" />
   </network-security-config>
   ```

7. Создать `app/src/main/res/xml/data_extraction_rules.xml`:
   ```xml
   <data-extraction-rules>
       <cloud-backup>
           <exclude domain="root" />
           <exclude domain="file" />
           <exclude domain="database" />
           <exclude domain="sharedpref" />
       </cloud-backup>
       <device-transfer>
           <exclude domain="root" />
           <exclude domain="file" />
           <exclude domain="database" />
           <exclude domain="sharedpref" />
       </device-transfer>
   </data-extraction-rules>
   ```
</task>

<task id="6" name="ProGuard-правила и .gitignore" type="feat">
1. Создать `app/proguard-rules.pro` с правилами для:
   - Lazysodium + JNA (keep com.sun.jna.**, com.goterl.lazysodium.**)
   - Tink (keep com.google.crypto.tink.**)
   - Room (keep RoomDatabase, @Entity)

2. Обновить корневой `.gitignore` — добавить стандартные исключения Android-проекта:
   ```
   .gradle/
   build/
   local.properties
   *.iml
   .idea/
   captures/
   .externalNativeBuild/
   .cxx/
   *.apk
   *.aab
   ```
</task>

## must_haves

- [ ] Проект компилируется командой `./gradlew assembleDebug` без ошибок
- [ ] Все зависимости фазы 1 указаны с точными версиями в `libs.versions.toml`
- [ ] `namespace = "ru.cheburmail.app"`, `minSdk = 26`, `targetSdk = 35`, `compileSdk = 35`
- [ ] `android:allowBackup="false"` и `android:fullBackupContent="false"` в манифесте
- [ ] JNA подключена как `@aar` (не plain JAR)
- [ ] Приложение запускается на эмуляторе API 26+ и показывает Compose-экран

## Верификация

1. `./gradlew assembleDebug` — успешная сборка
2. `./gradlew dependencies --configuration releaseRuntimeClasspath | grep lazysodium` — Lazysodium 5.2.0 в дереве зависимостей
3. `./gradlew dependencies --configuration releaseRuntimeClasspath | grep tink` — Tink 1.8.0 в дереве зависимостей
4. Установить APK на эмулятор API 26, убедиться что приложение запускается и отображает текст "CheburMail"
