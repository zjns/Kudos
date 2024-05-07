package com.kanyun.kudos.annotations

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
annotation class KudosJsonName(val name: String)
