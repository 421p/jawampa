package com.github.sshaddicts.jawampa.transport.netty

import com.github.sshaddicts.jawampa.connection.IWampClientConnectionConfig
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.ssl.SslContext

class NettyWampConnectionConfig internal constructor(internal var sslContext: SslContext, maxFramePayloadLength: Int, httpHeaders: HttpHeaders) : IWampClientConnectionConfig {
    var maxFramePayloadLength: Int = 0
        internal set
    var httpHeaders: HttpHeaders
        internal set

    init {
        this.maxFramePayloadLength = maxFramePayloadLength
        this.httpHeaders = httpHeaders
    }

    /**
     * the SslContext which will be used to create Ssl connections to the WAMP
     * router. If this is set to null a default (unsecure) SSL client context will be created
     * and used.
     */
    fun sslContext(): SslContext {
        return sslContext
    }

    /**
     * Builder class that must be used to create a [NettyWampConnectionConfig]
     * instance.
     */
    class Builder {

        internal lateinit var sslContext: SslContext
        internal var maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH
        internal var httpHeaders: HttpHeaders = DefaultHttpHeaders()

        /**
         * Allows to set the SslContext which will be used to create Ssl connections to the WAMP
         * router. If this is set to null a default (unsecure) SSL client context will be created
         * and used.
         *
         * @param sslContext The SslContext that will be used for SSL connections.
         * @return The [Builder] object
         */
        fun withSslContext(sslContext: SslContext): Builder {
            this.sslContext = sslContext
            return this
        }

        fun withMaxFramePayloadLength(maxFramePayloadLength: Int): Builder {
            if (maxFramePayloadLength <= 0) {
                throw IllegalArgumentException("maxFramePayloadLength parameter cannot be negative")
            }
            this.maxFramePayloadLength = maxFramePayloadLength
            return this
        }

        /**
         * Add a new header with the specified name and value.
         *
         * @param name  The name of the header being added.
         * @param value The value of the header being added.
         * @return The [Builder] object.
         */
        fun withHttpHeader(name: String, value: Any): Builder {
            this.httpHeaders.add(name, value)
            return this
        }

        fun build(): NettyWampConnectionConfig {
            return NettyWampConnectionConfig(sslContext, maxFramePayloadLength, httpHeaders)
        }
    }

    companion object {

        internal val DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535
    }
}
