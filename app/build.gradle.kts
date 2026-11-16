
plugins {
        id("com.android.application")
            id("org.jetbrains.kotlin.android")
                id("org.jetbrains.kotlin.plugin.compose")
}

android {
        namespace = "com.universaltranslator"
            compileSdk = 36

                defaultConfig {
                            applicationId = "com.universaltranslator"
                                    minSdk = 26
                                            targetSdk = 35
                                                    versionCode = 4
                                                            versionName = "1.4.0"
                }

                    // Android release signing
                        signingConfigs {
                                    create("release") {
                                                    val keystorePath = System.getenv("CM_KEYSTORE_PATH")

                                                                if (!keystorePath.isNullOrBlank()) {
                                                                                    storeFile = file(keystorePath)
                                                                                                    storePassword = System.getenv("CM_KEYSTORE_PASSWORD")
                                                                                                                        ?: error("Keystore password missing")
                                                                                                                                        keyAlias = System.getenv("CM_KEY_ALIAS")
                                                                                                                                                            ?: error("Key alias missing")
                                                                                                                                                                            keyPassword = System.getenv("CM_KEY_PASSWORD")
                                                                                                                                                                                                ?: error("Key password missing")
                                                                }
                                    }
                        }

                            buildTypes {
                                        debug {
                                                        isMinifyEnabled = false
                                        }

                                                release {
                                                                isMinifyEnabled = false

                                                                            // Sign only when Codemagic provides a keystore
                                                                                        if (!System.getenv("CM_KEYSTORE_PATH").isNullOrBlank()) {
                                                                                                            signingConfig = signingConfigs.getByName("release")
                                                                                        }

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

dependencies {
        // Jetpack Compose
            implementation(platform("androidx.compose:compose-bom:2025.10.00"))
                implementation("androidx.activity:activity-compose:1.11.0")
                    implementation("androidx.compose.ui:ui")
                        implementation("androidx.compose.ui:ui-tooling-preview")
                            implementation("androidx.compose.material3:material3")
                                implementation("androidx.compose.material:material-icons-extended")

                                    // Android lifecycle
                                        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")

                                            // Material Components
                                                implementation("com.google.android.material:material:1.13.0")

                                                    // ML Kit OCR
                                                        implementation("com.google.mlkit:text-recognition:16.0.1")
                                                            implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
                                                                implementation("com.google.mlkit:text-recognition-korean:16.0.1")
                                                                    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
                                                                        implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")

                                                                            // Language identification
                                                                                implementation("com.google.mlkit:language-id:17.0.6")

                                                                                    // ML Kit translation
                                                                                        implementation("com.google.mlkit:translate:17.0.3")

                                                                                            // Kotlin coroutines
                                                                                                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
a
                                                                                                    )
                                                                }
                                    }
                }
}