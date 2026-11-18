pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

rootProject.name = "LUXI-MangaExtension"

include(":core")

// Auto-include lib/* modules
file("lib").listFiles()?.filter { it.isDirectory && file("${it.path}/build.gradle.kts").exists() }?.forEach {
    include(":lib:${it.name}")
}

// Auto-include lib-multisrc/* modules
file("lib-multisrc").listFiles()?.filter { it.isDirectory && file("${it.path}/build.gradle.kts").exists() }?.forEach {
    include(":lib-multisrc:${it.name}")
}

// Auto-include src/<lang>/<name> extension modules
file("src").listFiles()?.filter { it.isDirectory }?.forEach { langDir ->
    langDir.listFiles()?.filter {
        it.isDirectory && (file("${it.path}/build.gradle.kts").exists() || file("${it.path}/build.gradle").exists())
    }?.forEach { extDir ->
        include(":src:${langDir.name}:${extDir.name}")
    }
}
