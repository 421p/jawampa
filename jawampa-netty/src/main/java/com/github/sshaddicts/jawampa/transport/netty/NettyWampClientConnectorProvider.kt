/*
 * Copyright 2014 Matthias Einwag
 *
 * The jawampa authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.github.sshaddicts.jawampa.transport.netty

import com.github.sshaddicts.jawampa.ApplicationError
import com.github.sshaddicts.jawampa.WampMessages.WampMessage
import com.github.sshaddicts.jawampa.WampSerialization
import com.github.sshaddicts.jawampa.connection.*
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.net.URI
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import javax.net.ssl.SSLException

/**
 * Returns factory methods for the establishment of WAMP connections between
 * clients and routers.<br></br>
 */
class NettyWampClientConnectorProvider : IWampConnectorProvider {

    override fun createScheduler(): ScheduledExecutorService {
        return NioEventLoopGroup(1, ThreadFactory { r ->
            val t = Thread(r, "WampClientEventLoop")
            t.isDaemon = true
            t
        })
    }

    @Throws(Exception::class)
    override fun createConnector(uri: URI, configuration: IWampClientConnectionConfig, serializations: List<WampSerialization>): IWampConnector {

        var scheme: String? = uri.scheme
        scheme = if (scheme != null) scheme else ""

        // Check if the configuration is a netty configuration.
        // However null is an allowed value
        val nettyConfig: NettyWampConnectionConfig?
        if (configuration is NettyWampConnectionConfig) {
            nettyConfig = configuration
        } else if (configuration != null) {
            throw ApplicationError(ApplicationError.INVALID_CONNECTION_CONFIGURATION)
        } else {
            nettyConfig = null
        }

        if (scheme.equals("ws", ignoreCase = true) || scheme.equals("wss", ignoreCase = true)) {

            // Check the host and port field for validity
            if (uri.host == null || uri.port == 0) {
                throw ApplicationError(ApplicationError.INVALID_URI)
            }

            // Initialize SSL when required
            val needSsl = uri.scheme.equals("wss", ignoreCase = true)
            val sslCtx0: SslContext?
            if (needSsl && (nettyConfig == null || nettyConfig.sslContext() == null)) {
                // Create a default SslContext when we got none provided through the constructor
                try {
                    sslCtx0 = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build()
                } catch (e: SSLException) {
                    throw e
                }

            } else if (needSsl) {
                sslCtx0 = nettyConfig!!.sslContext()
            } else {
                sslCtx0 = null
            }

            val subProtocols = WampSerialization.makeWebsocketSubprotocolList(serializations)

            val maxFramePayloadLength = nettyConfig?.maxFramePayloadLength ?: NettyWampConnectionConfig.DEFAULT_MAX_FRAME_PAYLOAD_LENGTH
            val httpHeaders = if (nettyConfig == null) DefaultHttpHeaders() else nettyConfig.httpHeaders

            // Return a factory that creates a channel for websocket connections
            return object : IWampConnector {
                override fun connect(scheduler: ScheduledExecutorService,
                                     connectListener: IPendingWampConnectionListener,
                                     connectionListener: IWampConnectionListener): IPendingWampConnection {

                    // Use well-known ports if not explicitly specified
                    val port: Int
                    if (uri.port == -1) {
                        if (needSsl)
                            port = 443
                        else
                            port = 80
                    } else
                        port = uri.port

                    val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                            uri, WebSocketVersion.V13, subProtocols,
                            false, httpHeaders, maxFramePayloadLength)

                    /**
                     * Netty handler for that receives and processes WampMessages and state
                     * events from the pipeline.
                     * A new instance of this is created for each connection attempt.
                     */
                    val connectionHandler = object : SimpleChannelInboundHandler<WampMessage>() {
                        internal var connectionWasEstablished = false
                        /** Guard to prevent forwarding events aftert the channel was closed  */
                        internal var wasClosed = false

                        @Throws(Exception::class)
                        override fun channelInactive(ctx: ChannelHandlerContext) {
                            if (wasClosed) return
                            wasClosed = true
                            if (connectionWasEstablished) {
                                connectionListener.transportClosed()
                            } else {
                                // The transport closed before the websocket handshake was completed
                                connectListener.connectFailed(ApplicationError(ApplicationError.TRANSPORT_CLOSED))
                            }
                        }

                        @Throws(Exception::class)
                        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                            if (wasClosed) return
                            wasClosed = true
                            if (connectionWasEstablished) {
                                connectionListener.transportError(cause)
                            } else {
                                // The transport closed before the websocket handshake was completed
                                connectListener.connectFailed(cause)
                            }
                        }

                        @Throws(Exception::class)
                        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                            if (wasClosed) return
                            if (evt is ConnectionEstablishedEvent) {
                                val serialization = evt.serialization()

                                val connection = object : IWampConnection {
                                    override fun serialization(): WampSerialization {
                                        return serialization
                                    }

                                    override val isSingleWriteOnly: Boolean
                                        get() = false

                                    override fun sendMessage(message: WampMessage, promise: IWampConnectionPromise<Void>) {
                                        val f = ctx.writeAndFlush(message)
                                        f.addListener { future ->
                                            if (future.isSuccess || future.isCancelled)
                                                promise.fulfill(null)
                                            else
                                                promise.reject(future.cause())
                                        }
                                    }

                                    override fun close(sendRemaining: Boolean, promise: IWampConnectionPromise<Void>) {
                                        // sendRemaining is ignored. Remaining data is always sent
                                        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                                                .addListener {
                                                    (it as ChannelFuture).channel()
                                                            .close()
                                                            .addListener { future ->
                                                                if (future.isSuccess || future.isCancelled)
                                                                    promise.fulfill(null)
                                                                else
                                                                    promise.reject(future.cause())
                                                            }
                                                }
                                    }
                                }

                                connectionWasEstablished = true

                                // Connection to the remote host was established
                                // However the WAMP session is not established until the handshake was finished
                                connectListener.connectSucceeded(connection)
                            }
                        }

                        @Throws(Exception::class)
                        override fun channelRead0(ctx: ChannelHandlerContext, msg: WampMessage) {
                            if (wasClosed) return
                            assert(connectionWasEstablished)
                            connectionListener.messageReceived(msg)
                        }
                    }

                    // If the assigned scheduler is a netty eventloop use this
                    val nettyEventLoop: EventLoopGroup
                    if (scheduler is EventLoopGroup) {
                        nettyEventLoop = scheduler
                    } else {
                        connectListener.connectFailed(ApplicationError(ApplicationError.INCOMATIBLE_SCHEDULER))
                        return IPendingWampConnection.Dummy
                    }

                    val b = Bootstrap()
                    b.group(nettyEventLoop)
                            .channel(NioSocketChannel::class.java)
                            .handler(object : ChannelInitializer<SocketChannel>() {
                                override fun initChannel(ch: SocketChannel) {
                                    val p = ch.pipeline()
                                    if (sslCtx0 != null) {
                                        p.addLast(sslCtx0.newHandler(ch.alloc(),
                                                uri.host,
                                                port))
                                    }
                                    p.addLast(
                                            HttpClientCodec(),
                                            HttpObjectAggregator(8192),
                                            WebSocketClientProtocolHandler(handshaker, false),
                                            WebSocketFrameAggregator(WampHandlerConfiguration.MAX_WEBSOCKET_FRAME_SIZE),
                                            WampClientWebsocketHandler(handshaker),
                                            connectionHandler)
                                }
                            })

                    val connectFuture = b.connect(uri.host, port)
                    connectFuture.addListener { future ->
                        if (future.isSuccess) {
                            // Do nothing. The connection is only successful when the websocket handshake succeeds
                        } else {
                            // Remark: Might be called directly in addListener
                            // Therefore addListener should be the last call
                            // Remark2: This branch will be taken upon cancellation.
                            // This is required by the contract.
                            connectListener.connectFailed(future.cause())
                        }
                    }

                    // Return the connection in progress with the ability for cancellation
                    return object : IPendingWampConnection {
                        override fun cancelConnect() {
                            connectFuture.cancel(false)
                        }
                    }
                }
            }
        }

        throw ApplicationError(ApplicationError.INVALID_URI)

    }
}
