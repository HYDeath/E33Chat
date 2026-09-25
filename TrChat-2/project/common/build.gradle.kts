dependencies {
    compileOnly(project(":project:module-chat"))
    compileOnly("com.eatthepath:fast-uuid:0.2.0")
}

taboolib { subproject = true }