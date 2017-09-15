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

import com.github.sshaddicts.jawampa.WampMessages.WampMessage
import com.github.sshaddicts.jawampa.WampRouter
import com.github.sshaddicts.jawampa.WampSerialization
import com.github.sshaddicts.jawampa.connection.IWampConnection
import com.github.sshaddicts.jawampa.connection.IWampConnectionAcceptor
import com.github.sshaddicts.jawampa.connection.IWampConnectionPromise
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaderNames.HOST
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.ssl.SslHandler
import io.netty.util.AsciiString
import io.netty.util.ReferenceCountUtil

/**
 * A websocket server adapter for WAMP that integrates into a Netty pipeline.
 */
class WampServerWebsocketHandler @JvmOverloads constructor(internal val websocketPath: String, internal val router: WampRouter,
                                                           internal val supportedSerializations: List<WampSerialization>? = WampSerialization.defaultSerializations()) : ChannelInboundHandlerAdapter() {
    internal val connectionAcceptor: IWampConnectionAcceptor = router.connectionAcceptor()

    internal var serialization = WampSerialization.Invalid
    internal var handshakeInProgress = false

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val request = msg as? FullHttpRequest

        // Check for invalid http messages during handshake
        if (request != null && handshakeInProgress) {
            request.release()
            sendBadRequestAndClose(ctx, null)
            return
        }

        // Transform this when we have an upgrade for our path,
        // otherwise pass the message
        if (request != null && isUpgradeRequest(request)) {
            try {
                tryWebsocketHandshake(ctx, msg as FullHttpRequest)
            } finally {
                request.release()
            }
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    private fun isUpgradeRequest(request: FullHttpRequest): Boolean {
        if (!request.decoderResult().isSuccess) {
            return false
        }

        val connectionHeaderValue = request.headers().get(HttpHeaderNames.CONNECTION) ?: return false
        val connectionHeaderString = AsciiString(connectionHeaderValue)
        val connectionHeaderFields = connectionHeaderString.toLowerCase().split(',')
        var hasUpgradeField = false
        val upgradeValue = HttpHeaderValues.UPGRADE.toLowerCase()
        for (s in connectionHeaderFields) {
            if (upgradeValue == s.trim()) {
                hasUpgradeField = true
                break
            }
        }
        if (!hasUpgradeField) {
            return false
        }

        return if (!request.headers().contains(
                HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true)) {
            false
        } else request.uri() == websocketPath

    }

    private fun tryWebsocketHandshake(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val wsLocation = getWebSocketLocation(ctx, request)
        val subProtocols = WampSerialization.Companion.makeWebsocketSubprotocolList(supportedSerializations)
        val handshaker = WebSocketServerHandshakerFactory(wsLocation,
                subProtocols,
                false,
                WampHandlerConfiguration.MAX_WEBSOCKET_FRAME_SIZE)
                .newHandshaker(request)

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel())
        } else {
            handshakeInProgress = true
            // The next statement will throw if the handshake gets wrong. This will lead to an
            // exception in the channel which will close the channel (which is OK).
            val handshakeFuture = handshaker.handshake(ctx.channel(), request)
            val actualProtocol = handshaker.selectedSubprotocol()
            serialization = WampSerialization.Companion.fromString(actualProtocol)

            // In case of unsupported websocket subprotocols we close the connection.
            // Won't help us when the client will ignore our protocol response and send
            // invalid packets anyway
            if (serialization === WampSerialization.Invalid) {
                handshakeFuture.addListener(ChannelFutureListener.CLOSE)
                return
            }

            // Remove all handlers after this one - we don't need them anymore since we switch to WAMP
            var last: ChannelHandler? = ctx.pipeline().last()
            while (last != null && last !== this) {
                ctx.pipeline().removeLast()
                last = ctx.pipeline().last()
            }

            if (last == null) {
                throw IllegalStateException("Can't find the WAMP server handler in the pipeline")
            }

            // Remove the WampServerWebSocketHandler and replace it with the protocol handler
            // which processes pings and closes
            val protocolHandler = ProtocolHandler()
            ctx.pipeline().replace(this, "wamp-websocket-protocol-handler", protocolHandler)
            val protocolHandlerCtx = ctx.pipeline().context(protocolHandler)

            // Handle websocket fragmentation before the deserializer
            protocolHandlerCtx.pipeline().addLast(WebSocketFrameAggregator(WampHandlerConfiguration.MAX_WEBSOCKET_FRAME_SIZE))

            // Install the serializer and deserializer
            protocolHandlerCtx.pipeline().addLast("wamp-serializer",
                    WampSerializationHandler(serialization))
            protocolHandlerCtx.pipeline().addLast("wamp-deserializer",
                    WampDeserializationHandler(serialization))

            // Retrieve a listener for this new connection
            val connectionListener = connectionAcceptor.createNewConnectionListener()

            // Create a Wamp connection interface on top of that
            val connection = WampServerConnection(serialization)

            val routerHandler = object : SimpleChannelInboundHandler<WampMessage>() {
                @Throws(Exception::class)
                override fun handlerAdded(ctx: ChannelHandlerContext?) {
                    // Gets called once the channel gets added to the pipeline
                    connection.ctx = ctx
                }

                @Throws(Exception::class)
                override fun channelActive(ctx: ChannelHandlerContext) {
                    connectionAcceptor.acceptNewConnection(connection, connectionListener)
                }

                @Throws(Exception::class)
                override fun channelInactive(ctx: ChannelHandlerContext) {
                    connectionListener.transportClosed()
                }

                @Throws(Exception::class)
                override fun channelRead0(ctx: ChannelHandlerContext, msg: WampMessage) {
                    connectionListener.messageReceived(msg)
                }

                @Throws(Exception::class)
                override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
                    ctx.close()
                    connectionListener.transportError(cause)
                }
            }

            // Install the router in the pipeline
            protocolHandlerCtx.pipeline().addLast("wamp-router", routerHandler)

            handshakeFuture.addListener { future ->
                if (!future.isSuccess) {
                    // The handshake was not successful.
                    // Close the channel without registering
                    ctx.fireExceptionCaught(future.cause()) // TODO: This is a race condition if the router did not yet accept the connection
                } else {
                    // We successfully sent out the handshake
                    // Notify the router of that fact
                    ctx.fireChannelActive()
                }
            }

            // TODO: Maybe there are frames incoming before the handshakeFuture is resolved
            // This might lead to frames getting sent to the router before it is activated
        }
    }

    private fun getWebSocketLocation(ctx: ChannelHandlerContext, req: FullHttpRequest): String {
        val location = req.headers().get(HOST) + websocketPath
        return if (ctx.pipeline().get(SslHandler::class.java) != null) {
            "wss://" + location
        } else {
            "ws://" + location
        }
    }

    @Throws(Exception::class)
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (cause is WebSocketHandshakeException) {
            sendBadRequestAndClose(ctx, cause.message)
        } else {
            ctx.close()
        }
    }

    // All methods inside the connection will be called from the WampRouters thread
    // This causes no problems on the ordering since they all will be called from
    // the same thread. And Netty is threadsafe
    internal class WampServerConnection(val serialization: WampSerialization) : IWampConnection {
        var ctx: ChannelHandlerContext? = null

        override fun serialization(): WampSerialization {
            return serialization
        }

        override val isSingleWriteOnly: Boolean
            get() = false

        override fun sendMessage(message: WampMessage, promise: IWampConnectionPromise<Void>) {
            val f = ctx!!.writeAndFlush(message)
            f.addListener { future ->
                if (future.isSuccess || future.isCancelled)
                    promise.fulfill(null)
                else
                    promise.reject(future.cause())
            }
        }

        override fun close(sendRemaining: Boolean, promise: IWampConnectionPromise<Void>) {
            ctx!!.writeAndFlush(Unpooled.EMPTY_BUFFER)
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

    class ProtocolHandler : ChannelInboundHandlerAdapter() {

        internal var readState = ReadState.Reading

        override fun handlerAdded(ctx: ChannelHandlerContext?) {}

        override fun channelActive(ctx: ChannelHandlerContext) {
            ctx.fireChannelActive()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            readState = ReadState.Closed
            ctx.fireChannelInactive()
        }

        @Throws(Exception::class)
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            // Discard messages when we are not reading
            if (readState != ReadState.Reading) {
                ReferenceCountUtil.release(msg)
                return
            }

            // We might receive http requests here when the whe clients sends something after the upgrade
            // request but we have not fully sent out the response and the http codec is still installed.
            // However that would be an error.
            if (msg is FullHttpRequest) {
                msg.release()
                WampServerWebsocketHandler.sendBadRequestAndClose(ctx, null)
                return
            }

            if (msg is PingWebSocketFrame) {
                // Respond to Pings with Pongs
                try {
                    ctx.writeAndFlush(PongWebSocketFrame())
                } finally {
                    msg.release()
                }
            } else if (msg is CloseWebSocketFrame) {
                // Echo the close and close the connection
                readState = ReadState.Closed
                ctx.writeAndFlush(msg).addListener(ChannelFutureListener.CLOSE)

            } else {
                ctx.fireChannelRead(msg)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            // Will be called either through an exception in channelRead
            // or when the websocket handshake fails
            readState = ReadState.Error
            ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE)
            ctx.fireExceptionCaught(cause)
        }

        internal enum class ReadState {
            Closed,
            Reading,
            Error
        }
    }

    companion object {

        private fun sendBadRequestAndClose(ctx: ChannelHandlerContext, message: String?) {
            val response: FullHttpResponse
            if (message != null) {
                response = DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
                        Unpooled.wrappedBuffer(message.toByteArray()))
            } else {
                response = DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.BAD_REQUEST)
            }
            ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
    }
}
