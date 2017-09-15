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

import com.fasterxml.jackson.databind.node.ArrayNode
import com.github.sshaddicts.jawampa.WampMessages.WampMessage
import com.github.sshaddicts.jawampa.WampSerialization
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.http.websocketx.*
import io.netty.util.internal.logging.InternalLoggerFactory

class WampDeserializationHandler(internal val serialization: WampSerialization) : MessageToMessageDecoder<WebSocketFrame>() {
    internal var readState = ReadState.Closed

    fun serialization(): WampSerialization {
        return serialization
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {
        readState = ReadState.Reading
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        readState = ReadState.Reading
        ctx.fireChannelActive()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        readState = ReadState.Closed
        ctx.fireChannelInactive()
    }

    @Throws(Exception::class)
    override fun decode(ctx: ChannelHandlerContext, frame: WebSocketFrame, out: MutableList<Any>) {
        if (readState != ReadState.Reading) return

        val objectMapper = serialization.objectMapper
        if (frame is TextWebSocketFrame) {
            // Only want Text frames when text subprotocol
            if (!serialization.isText)
                throw IllegalStateException("Received unexpected TextFrame")

// If we receive an invalid frame on of the following functions will throw
            // This will lead Netty to closing the connection
            val arr = objectMapper!!.readValue(
                    ByteBufInputStream(frame.content()), ArrayNode::class.java)

            if (logger.isDebugEnabled) {
                logger.debug("Deserialized Wamp Message: {}", arr.toString())
            }

            val recvdMessage = WampMessage.Companion.fromObjectArray(arr)
            out.add(recvdMessage!!)
        } else if (frame is BinaryWebSocketFrame) {
            // Only want Binary frames when binary subprotocol
            if (serialization.isText)
                throw IllegalStateException("Received unexpected BinaryFrame")

// If we receive an invalid frame on of the following functions will throw
            // This will lead Netty to closing the connection
            val arr = objectMapper!!.readValue(
                    ByteBufInputStream(frame.content()), ArrayNode::class.java)

            if (logger.isDebugEnabled) {
                logger.debug("Deserialized Wamp Message: {}", arr.toString())
            }

            val recvdMessage = WampMessage.Companion.fromObjectArray(arr)
            out.add(recvdMessage!!)
        } else if (frame is PongWebSocketFrame) {
            // System.out.println("WebSocket Client received pong");
        } else if (frame is CloseWebSocketFrame) {
            // System.out.println("WebSocket Client received closing");
            readState = ReadState.Closed
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // We caught an exception.
        // Most probably because we received an invalid message
        readState = ReadState.Error
        ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                .addListener(ChannelFutureListener.CLOSE)
    }

    internal enum class ReadState {
        Closed,
        Reading,
        Error
    }

    companion object {

        private val logger = InternalLoggerFactory.getInstance(WampDeserializationHandler::class.java)
    }
}
