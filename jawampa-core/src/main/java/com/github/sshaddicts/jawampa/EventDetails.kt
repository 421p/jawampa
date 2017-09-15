package com.github.sshaddicts.jawampa

class EventDetails<T>(internal val message: T, internal val topic: String) {

    fun message(): T {
        return message
    }

    fun topic(): String {
        return topic
    }


}
