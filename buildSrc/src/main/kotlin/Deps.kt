object Deps {

    object Kotlin {
        const val gradlePlugin = "org.jetbrains.kotlin:kotlin-gradle-plugin:${Version.baseKotlin}"
    }

    object Kotlinx {
        const val serializationCore = "org.jetbrains.kotlinx:kotlinx-serialization-core:${Version.kotlinxSerialization}"
        const val serializationJson = "org.jetbrains.kotlinx:kotlinx-serialization-json:${Version.kotlinxSerialization}"
        const val coroutinesAndroid = "org.jetbrains.kotlinx:kotlinx-coroutines-android:${Version.kotlinxCoroutines}"
        const val coroutinesCore = "org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.kotlinxCoroutines}"
        const val dateTime = "org.jetbrains.kotlinx:kotlinx-datetime:0.4.0"
    }

    object Stately {
        const val common = "co.touchlab:stately-common:${Version.stately}"
        const val concurrency = "co.touchlab:stately-concurrency:${Version.stately}"
        const val isolate = "co.touchlab:stately-isolate:${Version.stately}"
        const val collections = "co.touchlab:stately-iso-collections:${Version.stately}"
    }

    object Test {
        const val core = "androidx.test:core:${Version.testCore}"
        const val coroutinesTest = "org.jetbrains.kotlinx:kotlinx-coroutines-test:${Version.kotlinxCoroutines}"
        const val junit = "junit:junit:${Version.junit}"
    }
}