plugins {
    id("com.android.application")
}

val releaseSigningVariables = mapOf(
    "TAPZIQ_RELEASE_STORE_FILE" to providers.environmentVariable("TAPZIQ_RELEASE_STORE_FILE"),
    "TAPZIQ_RELEASE_STORE_PASSWORD" to providers.environmentVariable("TAPZIQ_RELEASE_STORE_PASSWORD"),
    "TAPZIQ_RELEASE_KEY_ALIAS" to providers.environmentVariable("TAPZIQ_RELEASE_KEY_ALIAS"),
    "TAPZIQ_RELEASE_KEY_PASSWORD" to providers.environmentVariable("TAPZIQ_RELEASE_KEY_PASSWORD")
)
val hasCompleteReleaseSigning = releaseSigningVariables.values.all {
    it.orNull?.isNotBlank() == true
}

android {
    namespace = "com.tapziq.keyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tapziq.keyboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("production") {
                storeFile = file(releaseSigningVariables.getValue(
                    "TAPZIQ_RELEASE_STORE_FILE"
                ).get())
                storePassword = releaseSigningVariables.getValue(
                    "TAPZIQ_RELEASE_STORE_PASSWORD"
                ).get()
                keyAlias = releaseSigningVariables.getValue("TAPZIQ_RELEASE_KEY_ALIAS").get()
                keyPassword = releaseSigningVariables.getValue(
                    "TAPZIQ_RELEASE_KEY_PASSWORD"
                ).get()
                storeType = "PKCS12"
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails production packaging unless every release signing input is valid."

    doLast {
        val missing = releaseSigningVariables.filterValues {
            it.orNull?.isNotBlank() != true
        }.keys
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Production signing is required. Missing: ${missing.sorted().joinToString()}"
            )
        }

        val keystore = file(releaseSigningVariables.getValue(
            "TAPZIQ_RELEASE_STORE_FILE"
        ).get())
        if (!keystore.isFile) {
            throw GradleException("Production keystore does not exist: $keystore")
        }
    }
}

val productionArtifactTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "signReleaseBundle"
)

tasks.matching { it.name in productionArtifactTaskNames }.configureEach {
    dependsOn(verifyReleaseSigning)
}

tasks.register("checkProductionSigningTaskCoverage") {
    group = "verification"
    description = "Checks that every production artifact task requires signing verification."

    doLast {
        val signingGate = verifyReleaseSigning.get()
        val uncovered = productionArtifactTaskNames.filter { taskName ->
            val artifactTask = tasks.findByName(taskName) ?: return@filter true
            signingGate !in artifactTask.taskDependencies.getDependencies(artifactTask)
        }
        if (uncovered.isNotEmpty()) {
            throw GradleException(
                "Production artifact tasks bypass signing verification: " +
                    uncovered.sorted().joinToString()
            )
        }
    }
}
