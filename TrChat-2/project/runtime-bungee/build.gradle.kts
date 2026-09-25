dependencies {
    compileOnly(project(":project:common"))
    compileOnly(project(":project:module-chat"))
    compileOnly("net.md-5:bungeecord-api:1.21-R0.3")
}

taboolib { subproject = true }