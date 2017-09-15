package com.github.sshaddicts.jawampa.connection

/**
 * A lightweight future type which is used for the implementation
 * of WAMP connection adapters.
 */
interface IWampConnectionFuture<T> {
    /** Whether the operation completed with success or an error  */
    val isSuccess: Boolean

    /** The result of the operation (if the operation completed with success)  */
    fun result(): T?

    /** An error reason (if the operation completed with an error  */
    fun error(): Throwable?

    /** Returns an arbitrary state object which is stored inside the future  */
    fun state(): Any?
}