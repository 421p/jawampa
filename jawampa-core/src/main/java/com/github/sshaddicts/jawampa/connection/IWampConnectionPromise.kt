package com.github.sshaddicts.jawampa.connection

interface IWampConnectionPromise<T> : IWampConnectionFuture<T> {

    fun fulfill(value: T?)

    fun reject(error: Throwable?)

    override val isSuccess: Boolean

    override fun error(): Throwable?

    companion object {

        /**
         * A default implementation of the promise whose instance methods do nothing.<br></br>
         * Can be used in cases where the caller is not interested in the call results.
         */
        val Empty: IWampConnectionPromise<Void> = object : IWampConnectionPromise<Void> {
            override fun result(): Void? {
                return null
            }

            override fun state(): Any? {
                return null
            }

            override fun fulfill(value: Void?) {

            }

            override fun reject(error: Throwable?) {

            }

            override val isSuccess: Boolean
                get() = false

            override fun error(): Throwable? {
                return null
            }
        }
    }
}