plugins {
    id("com.android.application")
}

val tapziqVersionNameProperty = providers.gradleProperty("tapziqVersionName")
val tapziqVersionCodeProperty = providers.gradleProperty("tapziqVersionCode")
val tapziqSourceVersionName = "0.3.0"
val tapziqSourceVersionCode = 3000
val configuredVersionName = tapziqVersionNameProperty.orElse(tapziqSourceVersionName)
val configuredVersionCode = tapziqVersionCodeProperty.map { rawVersionCode ->
    rawVersionCode.toIntOrNull()
        ?: throw GradleException("tapziqVersionCode must be a positive integer.")
}.orElse(tapziqSourceVersionCode)

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
        versionCode = configuredVersionCode.get()
        versionName = configuredVersionName.get()

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
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("junit:junit:4.13.2")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails production packaging unless its version and signing inputs are valid."

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

        val versionName = tapziqVersionNameProperty.orNull
        val versionCodeText = tapziqVersionCodeProperty.orNull
        if (versionName.isNullOrBlank() || versionCodeText.isNullOrBlank()) {
            throw GradleException(
                "Production releases require -PtapziqVersionName and " +
                    "-PtapziqVersionCode."
            )
        }

        val versionMatch = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"
        ).matchEntire(versionName)
            ?: throw GradleException(
                "tapziqVersionName must be a stable semantic version such as 1.2.3."
            )
        if (!Regex("^[1-9][0-9]*$").matches(versionCodeText)) {
            throw GradleException("tapziqVersionCode must be a positive integer.")
        }
        if (
            versionName != tapziqSourceVersionName ||
            versionCodeText.toLongOrNull() != tapziqSourceVersionCode.toLong()
        ) {
            throw GradleException(
                "Production release version inputs must match the committed " +
                    "Tapziq source version."
            )
        }

        val major = versionMatch.groupValues[1].toLongOrNull()
            ?: throw GradleException("The semantic-version major component is too large.")
        val minor = versionMatch.groupValues[2].toLongOrNull()
            ?: throw GradleException("The semantic-version minor component is too large.")
        val patch = versionMatch.groupValues[3].toLongOrNull()
            ?: throw GradleException("The semantic-version patch component is too large.")
        if (major > 2100 || minor > 999 || patch > 999) {
            throw GradleException(
                "Semantic-version components exceed the Android versionCode limits."
            )
        }
        val expectedVersionCode = major * 1_000_000L + minor * 1_000L + patch
        if (expectedVersionCode !in 1L..2_100_000_000L) {
            throw GradleException(
                "The semantic version cannot be represented by an Android versionCode."
            )
        }
        if (versionCodeText.toLongOrNull() != expectedVersionCode) {
            throw GradleException(
                "tapziqVersionCode must be $expectedVersionCode for version $versionName."
            )
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
