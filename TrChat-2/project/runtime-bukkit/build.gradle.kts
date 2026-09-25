repositories {
    maven("https://nexus.scarsz.me/content/groups/public/")
}

dependencies {
    compileOnly(project(":project:common"))
    compileOnly(project(":project:module-adventure"))
    compileOnly(project(":project:module-chat"))
    compileOnly(project(":project:module-compat"))
    compileOnly(project(":project:module-nms"))
    compileOnly("ink.ptms.core:v260100:260100")
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.3")
    compileOnly("org.slf4j:slf4j-api:2.0.13")
    compileOnly("net.kyori:adventure-text-serializer-gson:4.26.1")
    compileOnly("net.kyori:adventure-text-minimessage:4.26.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    compileOnly(fileTree(rootDir.resolve("libs")))

    compileOnly("me.clip:placeholderapi:2.12.2") { isTransitive = false }
}

// DiscordSRV's upstream repository is currently unavailable. The integration
// source remains in the tree and can be re-enabled when its API is resolvable.
sourceSets.main { kotlin.exclude("**/ListenerDiscordSRV.kt") }
tasks.test { useJUnitPlatform() }

taboolib { subproject = true }
