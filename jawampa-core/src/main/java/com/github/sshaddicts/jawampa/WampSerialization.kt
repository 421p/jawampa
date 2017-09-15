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

package com.github.sshaddicts.jawampa

import com.fasterxml.jackson.databind.ObjectMapper
import org.msgpack.jackson.dataformat.MessagePackFactory
import java.util.*

/**
 * Possible serialization methods for WAMP
 */
enum class WampSerialization(private val stringValue: String, val isText: Boolean, val objectMapper: ObjectMapper?) {
    /** Used for cases where the serialization could not be negotiated  */
    Invalid("", true, null),
    /** Use the JSON serialization  */
    Json("wamp.2.json", true, ObjectMapper()),
    /** Use the MessagePack serialization  */
    MessagePack("wamp.2.msgpack", false, ObjectMapper(MessagePackFactory()));

    override fun toString(): String {
        return stringValue
    }

    companion object {

        fun fromString(serialization: String?): WampSerialization {
            return when (serialization) {
                null -> Invalid
                "wamp.2.json" -> Json
                "wamp.2.msgpack" -> MessagePack
                else -> Invalid
            }
        }

        fun makeWebsocketSubprotocolList(serializations: List<WampSerialization>?): String {
            val subProtocolBuilder = StringBuilder()
            var first = true
            serializations?.forEach { serialization ->
                if (!first) subProtocolBuilder.append(',')
                first = false
                subProtocolBuilder.append(serialization.toString())
            }

            return subProtocolBuilder.toString()
        }

        fun addDefaultSerializations(serializations: MutableList<WampSerialization>) {
            serializations.add(Json)
            serializations.add(MessagePack)
        }

        private val defaultSerializationList: List<WampSerialization>

        init {
            val l = ArrayList<WampSerialization>()
            addDefaultSerializations(l)
            defaultSerializationList = Collections.unmodifiableList(l)
        }

        fun defaultSerializations(): List<WampSerialization> {
            return defaultSerializationList
        }
    }
}
