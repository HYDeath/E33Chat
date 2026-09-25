package me.arasple.mc.trchat.module.internal.hook

import taboolib.common.platform.Platform

@Target(AnnotationTarget.CLASS)
annotation class Hook(val platforms: Array<Platform> = [])
