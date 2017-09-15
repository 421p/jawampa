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
import com.github.sshaddicts.jawampa.WampRouter
import com.github.sshaddicts.jawampa.WampSerialization
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE
import io.netty.handler.codec.http.HttpMethod.GET
import io.netty.handler.codec.http.HttpResponseStatus.*
import io.netty.handler.codec.http.HttpVersion.HTTP_1_1
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.CharsetUtil
import java.net.URI
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A simple default implementation for the websocket adapter for a WAMP router.<br></br>
 * It provides listening capabilities for a WAMP router on a given websocket address.
 */
class SimpleWampWebsocketListener @Throws(ApplicationError::class)
@JvmOverloads constructor(internal val router: WampRouter, internal val uri: URI, sslContext: SslContext,
                          internal var serializations: List<WampSerialization>? = WampSerialization.Companion.defaultSerializations()) {

    internal val bossGroup: EventLoopGroup
    internal val clientGroup: EventLoopGroup
    internal var state = State.Intialized
    internal var sslCtx: SslContext? = null
    internal var channel: Channel? = null
    internal var started = false

    init {

        if (serializations == null || serializations!!.size == 0 || serializations!!.contains(WampSerialization.Invalid))
            throw ApplicationError(ApplicationError.Companion.INVALID_SERIALIZATIONS)

        this.bossGroup = NioEventLoopGroup(1, ThreadFactory { r -> Thread(r, "WampRouterBossLoop") })
        this.clientGroup = NioEventLoopGroup(Runtime.getRuntime().availableProcessors(), object : ThreadFactory {
            private val counter = AtomicInteger()

            override fun newThread(r: Runnable): Thread {
                return Thread(r, "WampRouterClientLoop-" + counter.incrementAndGet())
            }
        })

        // Copy the ssl context only when we really want ssl
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            this.sslCtx = sslContext
        }
    }

    fun start() {
        if (state != State.Intialized) return

        try {
            // Initialize SSL when required
            if (uri.scheme.equals("wss", ignoreCase = true) && sslCtx == null) {
                // Use a self signed certificate when we got none provided through the constructor
                val ssc = SelfSignedCertificate()
                sslCtx = SslContextBuilder
                        .forServer(ssc.certificate(), ssc.privateKey())
                        .build()
            }

            // Use well-known ports if not explicitly specified
            val port: Int
            if (uri.port == -1) {
                if (sslCtx != null)
                    port = 443
                else
                    port = 80
            } else
                port = uri.port

            val b = ServerBootstrap()
            b.group(bossGroup, clientGroup)
                    .channel(NioServerSocketChannel::class.java)
                    .childHandler(WebSocketServerInitializer(uri, sslCtx))

            channel = b.bind(uri.host, port).sync().channel()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }

    }

    fun stop() {
        if (state == State.Closed) return

        if (channel != null) {
            try {
                channel!!.close().sync()
            } catch (e: InterruptedException) {
            }

            channel = null
        }

        bossGroup.shutdownGracefully()
        clientGroup.shutdownGracefully()

        state = State.Closed
    }

    internal enum class State {
        Intialized,
        Started,
        Closed
    }

    /**
     * Handles handshakes and messages
     */
    class WebSocketServerHandler internal constructor(private val uri: URI) : SimpleChannelInboundHandler<FullHttpRequest>() {

        private fun sendHttpResponse(
                ctx: ChannelHandlerContext, req: FullHttpRequest, res: FullHttpResponse) {
            // Generate an error page if response getStatus code is not OK (200).
            if (res.status().code() != 200) {
                val buf = Unpooled.copiedBuffer(res.status().toString(), CharsetUtil.UTF_8)
                res.content().writeBytes(buf)
                buf.release()
                HttpUtil.setContentLength(res, res.content().readableBytes().toLong())
            }
            // Send the response and close the connection if necessary.
            val f = ctx.channel().writeAndFlush(res)
            if (!HttpUtil.isKeepAlive(req) || res.status().code() != 200) {
                f.addListener(ChannelFutureListener.CLOSE)
            }
        }

        public override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            handleHttpRequest(ctx, msg)
        }

        private fun handleHttpRequest(ctx: ChannelHandlerContext, req: FullHttpRequest) {
            // Handle a bad request.
            if (!req.decoderResult().isSuccess) {
                sendHttpResponse(ctx, req, DefaultFullHttpResponse(HTTP_1_1, BAD_REQUEST))
                return
            }
            // Allow only GET methods.
            if (req.method() !== GET) {
                sendHttpResponse(ctx, req, DefaultFullHttpResponse(HTTP_1_1, FORBIDDEN))
                return
            }
            // Send the demo page and favicon.ico
            if ("/" == req.uri()) {
                val content = Unpooled.copiedBuffer(
                        "<html><head><title>Wamp Router</title></head><body>" +
                                "<h1>This server provides a wamp router on path " +
                                uri.path + "</h1>" +
                                "</body></html>", CharsetUtil.UTF_8)
                val res = DefaultFullHttpResponse(HTTP_1_1, OK, content)
                res.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8")
                HttpUtil.setContentLength(res, content.readableBytes().toLong())
                sendHttpResponse(ctx, req, res)
                return
            }
            if ("/favicon.ico" == req.uri()) {
                val res = DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND)
                sendHttpResponse(ctx, req, res)
                return
            }

            val res = DefaultFullHttpResponse(HTTP_1_1, NOT_FOUND)
            sendHttpResponse(ctx, req, res)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            cause.printStackTrace()
            ctx.close()
        }
    }

    private inner class WebSocketServerInitializer(private val uri: URI, private val sslCtx: SslContext?) : ChannelInitializer<SocketChannel>() {

        @Throws(Exception::class)
        public override fun initChannel(ch: SocketChannel) {
            val pipeline = ch.pipeline()
            if (sslCtx != null) {
                pipeline.addLast(sslCtx.newHandler(ch.alloc()))
            }
            pipeline.addLast(HttpServerCodec())
            pipeline.addLast(HttpObjectAggregator(65536))
            pipeline.addLast(WampServerWebsocketHandler(if (uri.path.length == 0) "/" else uri.path, router,
                    serializations))
            pipeline.addLast(WebSocketServerHandler(uri))
        }
    }
}
