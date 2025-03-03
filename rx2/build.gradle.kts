@file:Suppress("UnstableApiUsage")

plugins { id("org.mobilenativefoundation.store.android") }

dependencies {
  implementation(libs.kotlinx.coroutines.rx2)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.rxjava)
  implementation(projects.store)

  testImplementation(kotlin("test"))
  testImplementation(libs.junit)
  testImplementation(libs.google.truth)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.kotlinx.coroutines.test)
}

android { namespace = "org.mobilenativefoundation.store.rx2" }
