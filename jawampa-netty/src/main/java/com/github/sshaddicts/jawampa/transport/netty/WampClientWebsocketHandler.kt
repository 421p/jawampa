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

import com.github.sshaddicts.jawampa.WampError
import com.github.sshaddicts.jawampa.WampSerialization
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler


class WampClientWebsocketHandler(internal val handshaker: WebSocketClientHandshaker) : ChannelInboundHandlerAdapter() {

    private lateinit var serialization: WampSerialization

    fun serialization(): WampSerialization {
        return serialization
    }

    @Throws(Exception::class)
    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.fireChannelActive()
    }

    @Throws(Exception::class)
    override fun channelInactive(ctx: ChannelHandlerContext) {
        ctx.fireChannelInactive()
    }

    @Throws(Exception::class)
    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is CloseWebSocketFrame) {
            //readState = ReadState.Closed;
            handshaker.close(ctx.channel(), msg)
                    .addListener(ChannelFutureListener.CLOSE)
        } else {
            ctx.fireChannelRead(msg)
        }
    }

    @Throws(Exception::class)
    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt === WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
            // Handshake is completed
            val actualProtocol = handshaker.actualSubprotocol()
            serialization = WampSerialization.Companion.fromString(actualProtocol)
            if (serialization === WampSerialization.Invalid) {
                throw WampError("Invalid Websocket Protocol")
            }

            // Install the serializer and deserializer
            ctx.pipeline()
                    .addAfter(ctx.name(), "wamp-deserializer",
                            WampDeserializationHandler(serialization))
            ctx.pipeline()
                    .addAfter(ctx.name(), "wamp-serializer",
                            WampSerializationHandler(serialization))

            // Fire the connection established event
            ctx.fireUserEventTriggered(ConnectionEstablishedEvent(serialization))

        } else {
            ctx.fireUserEventTriggered(evt)
        }
    }
}
