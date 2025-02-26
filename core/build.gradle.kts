plugins { id("org.mobilenativefoundation.store.multiplatform") }

kotlin {
  sourceSets {
    val commonMain by getting { dependencies { implementation(libs.kotlin.stdlib) } }
  }
}

android { namespace = "org.mobilenativefoundation.store.core" }
