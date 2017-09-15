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
import com.github.sshaddicts.jawampa.WampSerialization
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageEncoder
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.util.internal.logging.InternalLoggerFactory

class WampSerializationHandler(internal val serialization: WampSerialization) : MessageToMessageEncoder<WampMessage>() {

    fun serialization(): WampSerialization {
        return serialization
    }

    override fun handlerAdded(ctx: ChannelHandlerContext?) {}

    @Throws(Exception::class)
    override fun encode(ctx: ChannelHandlerContext, msg: WampMessage, out: MutableList<Any>) {
        val msgBuffer = Unpooled.buffer()
        val outStream = ByteBufOutputStream(msgBuffer)
        val objectMapper = serialization.objectMapper
        try {
            val node = msg.toObjectArray(objectMapper!!)
            objectMapper.writeValue(outStream, node)

            if (logger.isDebugEnabled) {
                logger.debug("Serialized Wamp Message: {}", node.toString())
            }

        } catch (e: Exception) {
            msgBuffer.release()
            return
        }

        if (serialization.isText) {
            val frame = TextWebSocketFrame(msgBuffer)
            out.add(frame)
        } else {
            val frame = BinaryWebSocketFrame(msgBuffer)
            out.add(frame)
        }
    }

    companion object {

        private val logger = InternalLoggerFactory.getInstance(WampSerializationHandler::class.java)
    }
}
