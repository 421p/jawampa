package com.github.sshaddicts.jawampa.connection

/**
 * A lightweight promise type which is used for the implementation
 * of WAMP connection adapters.<br></br>
 * The promise is not thread-safe and supports no synchronization.<br></br>
 * This means it is not allowed to call fulfill or reject more than once and
 * to access the result without external synchronization.
 */
class WampConnectionPromise<T>
/**
 * Creates a new promise
 * @param callback The callback which will be invoked when [WampConnectionPromise.fulfill]
 * or [WampConnectionPromise.reject] is called.
 * @param state An arbitrary state object which is stored inside the promise.
 */
(private var callback: ICompletionCallback<T>, internal var state: Any?) : IWampConnectionPromise<T> {
    internal var error: Throwable? = null
    internal var result: T? = null

    override fun state(): Any? {
        return state
    }

    override fun fulfill(value: T?) {
        this.result = value
        callback.onCompletion(this)
    }

    override fun reject(error: Throwable?) {
        this.error = error
        callback.onCompletion(this)
    }

    /**
     * Resets a promises state.<br></br>
     * This can be used to reuse a promise.<br></br>
     * This may only be used if it's guaranteed that the promise and the
     * associated future is no longer used by anyone else.
     */
    fun reset(callback: ICompletionCallback<T>, state: Any?) {
        this.error = null
        this.result = null
        this.callback = callback
        this.state = state
    }

    override val isSuccess: Boolean
        get() = error == null

    override fun result(): T? {
        return result
    }

    override fun error(): Throwable? {
        return error
    }
}