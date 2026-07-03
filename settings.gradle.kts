import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val localProperties = Properties()
val localPropertiesFile = File(rootDir, "local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

fun localProperty(name: String): String? =
    localProperties.getProperty(name) ?: System.getenv(name)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/lightphone/light-keyboard")
            credentials {
                username = localProperty("gpr.user")
                    ?: localProperty("GITHUB_ACTOR")
                    ?: ""
                password = localProperty("gpr.key")
                    ?: localProperty("GITHUB_TOKEN")
                    ?: ""
            }
        }
    }
}

rootProject.name = "Phono"
include(":light-ui")
include(":app")
