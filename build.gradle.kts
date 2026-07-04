plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.dokka)
  id("config-site")
  id("config-multi-deploy")
}

description = "A Kotlin Multiplatform library for downloading podcast episodes and cutting the injected ads out of them."
version = self.versions.name.get()
group = "com.episode6.tacita"

tasks.wrapper {
  gradleVersion = libs.versions.gradle.core.get()
  distributionType = Wrapper.DistributionType.ALL
}

kotlin {
  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.ktor.client.core)
        api(libs.okio.core)
        implementation(libs.kotlinx.coroutines.core)
      }
    }
    // by-name matching instead of `by getting` so CI shards that skip the jvm target still configure
    matching { it.name == "jvmMain" }.configureEach {
      dependencies {
        implementation(libs.ktor.client.okhttp)
      }
    }
    matching { it.name == "jvmTest" }.configureEach {
      dependencies {
        implementation(libs.ktor.client.mock)
        implementation(libs.assertk.jvm)
      }
    }
  }
}
