plugins { alias(libs.plugins.fabric.loom) }
group = "dev.projectileaura"
version = "0.1.0"
base { archivesName = "projectile-aura" }
repositories {
    maven("https://maven.meteordev.org/releases")
    maven("https://maven.meteordev.org/snapshots")
}
dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.meteor.client)
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}
