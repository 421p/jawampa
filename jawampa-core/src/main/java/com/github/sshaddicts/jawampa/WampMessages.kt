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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.*

class WampMessages {

    /**
     * Base class for all messages
     */
    abstract class WampMessage {

        @Throws(WampError::class)
        abstract fun toObjectArray(mapper: ObjectMapper): JsonNode

        companion object {

            @Throws(WampError::class)
            fun fromObjectArray(messageNode: ArrayNode?): WampMessage? {
                if (messageNode == null || messageNode.size() < 1
                        || !messageNode.get(0).canConvertToInt())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val messageType = messageNode.get(0).asInt()
                val factory = messageFactories[messageType] ?: return null
// We can't find the message type, so we skip it

                return factory.fromObjectArray(messageNode)
            }

            // Register all possible message types

            /**
             * A map which associates all message types which factories which can
             * recreate them from received data.
             */
            internal val messageFactories: Map<Int, WampMessageFactory>

            init {
                val map = HashMap<Int, WampMessageFactory>()
                map.put(HelloMessage.ID, HelloMessage.Factory())
                map.put(WelcomeMessage.ID, WelcomeMessage.Factory())
                map.put(AbortMessage.ID, AbortMessage.Factory())
                map.put(ChallengeMessage.ID, ChallengeMessage.Factory())
                map.put(AuthenticateMessage.ID, AuthenticateMessage.Factory())
                map.put(GoodbyeMessage.ID, GoodbyeMessage.Factory())
                // map.put(MessageType.ID, new HeartbeatMessage.Factory());
                map.put(ErrorMessage.ID, ErrorMessage.Factory())
                map.put(PublishMessage.ID, PublishMessage.Factory())
                map.put(PublishedMessage.ID, PublishedMessage.Factory())
                map.put(SubscribeMessage.ID, SubscribeMessage.Factory())
                map.put(SubscribedMessage.ID, SubscribedMessage.Factory())
                map.put(UnsubscribeMessage.ID, UnsubscribeMessage.Factory())
                map.put(UnsubscribedMessage.ID, UnsubscribedMessage.Factory())
                map.put(EventMessage.ID, EventMessage.Factory())
                map.put(CallMessage.ID, CallMessage.Factory())
                // map.put(CancelMessage.ID, new CancelMessage.Factory());
                map.put(ResultMessage.ID, ResultMessage.Factory())
                map.put(RegisterMessage.ID, RegisterMessage.Factory())
                map.put(RegisteredMessage.ID, RegisteredMessage.Factory())
                map.put(UnregisterMessage.ID, UnregisterMessage.Factory())
                map.put(UnregisteredMessage.ID, UnregisteredMessage.Factory())
                map.put(InvocationMessage.ID, InvocationMessage.Factory())
                // map.put(InterruptMessage.ID, new InterruptMessage.Factory());
                map.put(YieldMessage.ID, YieldMessage.Factory())
                messageFactories = Collections.unmodifiableMap(map)
            }
        }
    }

    internal interface WampMessageFactory {
        @Throws(WampError::class)
        fun fromObjectArray(messageNode: ArrayNode): WampMessage
    }

    /**
     * Sent by a Client to initiate opening of a WAMP session to a Router
     * attaching to a Realm. Format: [HELLO, Realm|uri, Details|dict]
     */
    class HelloMessage(var realm: String, var details: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(realm)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3 || !messageNode.get(1).isTextual
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val realm = messageNode.get(1).asText()
                val details = messageNode.get(2) as ObjectNode
                return HelloMessage(realm, details)
            }
        }

        companion object {
            val ID = 1
        }
    }

    /**
     * Sent by a Router to accept a Client. The WAMP session is now open.
     * Format: [WELCOME, Session|id, Details|dict]
     */
    class WelcomeMessage(var sessionId: Long, var details: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(sessionId)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val sessionId = messageNode.get(1).asLong()
                val details = messageNode.get(2) as ObjectNode
                return WelcomeMessage(sessionId, details)
            }
        }

        companion object {
            val ID = 2
        }
    }

    /**
     * Sent by a Peer to abort the opening of a WAMP session. No response is
     * expected. [ABORT, Details|dict, Reason|uri]
     */
    class AbortMessage(var details: ObjectNode?, var reason: String) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(reason)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3 || !messageNode.get(1).isObject
                        || !messageNode.get(2).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val details = messageNode.get(1) as ObjectNode
                val reason = messageNode.get(2).asText()
                return AbortMessage(details, reason)
            }
        }

        companion object {
            val ID = 3
        }
    }

    /**
     * During authenticated session establishment, a Router sends a challenge message.
     * Format: [CHALLENGE, AuthMethod|string, Extra|dict]
     */
    class ChallengeMessage(var authMethod: String, var extra: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(authMethod)
            if (extra != null)
                messageNode.add(extra)
            else
                messageNode.add(mapper.createObjectNode())
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).isTextual
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val authMethod = messageNode.get(1).asText()
                val extra = messageNode.get(2) as ObjectNode
                return ChallengeMessage(authMethod, extra)
            }
        }

        companion object {
            val ID = 4
        }
    }

    /**
     * A Client having received a challenge is expected to respond by sending a signature or token.
     * Format: [AUTHENTICATE, Signature|string, Extra|dict]
     */
    class AuthenticateMessage(var signature: String, var extra: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(signature)
            if (extra != null)
                messageNode.add(extra)
            else
                messageNode.add(mapper.createObjectNode())
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).isTextual
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val signature = messageNode.get(1).asText()
                val extra = messageNode.get(2) as ObjectNode
                return AuthenticateMessage(signature, extra)
            }
        }

        companion object {
            val ID = 5
        }
    }

    /**
     * Sent by a Peer to close a previously opened WAMP session. Must be echo'ed
     * by the receiving Peer. Format: [GOODBYE, Details|dict, Reason|uri]
     */
    class GoodbyeMessage(var details: ObjectNode?, var reason: String) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(reason)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3 || !messageNode.get(1).isObject
                        || !messageNode.get(2).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val details = messageNode.get(1) as ObjectNode
                val reason = messageNode.get(2).asText()
                return GoodbyeMessage(details, reason)
            }
        }

        companion object {
            val ID = 6
        }
    }

    /**
     * Error reply sent by a Peer as an error response to different kinds of
     * requests. Possible formats: [ERROR, REQUEST.Type|int, REQUEST.Request|id,
     * Details|dict, Error|uri] [ERROR, REQUEST.Type|int, REQUEST.Request|id,
     * Details|dict, Error|uri, Arguments|list] [ERROR, REQUEST.Type|int,
     * REQUEST.Request|id, Details|dict, Error|uri, Arguments|list,
     * ArgumentsKw|dict]
     */
    class ErrorMessage(var requestType: Int, var requestId: Long,
                       var details: ObjectNode?, var error: String, var arguments: ArrayNode?,
                       var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestType)
            messageNode.add(requestId)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(error)
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 5 || messageNode.size() > 7
                        || !messageNode.get(1).canConvertToInt()
                        || !messageNode.get(2).canConvertToLong()
                        || !messageNode.get(3).isObject
                        || !messageNode.get(4).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestType = messageNode.get(1).asInt()
                val requestId = messageNode.get(2).asLong()
                val details = messageNode.get(3) as ObjectNode
                val error = messageNode.get(4).asText()
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 6) {
                    if (!messageNode.get(5).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(5) as ArrayNode
                    if (messageNode.size() >= 7) {
                        if (!messageNode.get(6).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(6) as ObjectNode
                    }
                }

                return ErrorMessage(requestType, requestId, details, error,
                        arguments, argumentsKw)
            }
        }

        companion object {
            val ID = 8
        }
    }

    /**
     * Sent by a Publisher to a Broker to publish an event. Possible formats:
     * [PUBLISH, Request|id, Options|dict, Topic|uri] [PUBLISH, Request|id,
     * Options|dict, Topic|uri, Arguments|list] [PUBLISH, Request|id,
     * Options|dict, Topic|uri, Arguments|list, ArgumentsKw|dict]
     */
    class PublishMessage(var requestId: Long, var options: ObjectNode?, var topic: String,
                         var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (options != null)
                messageNode.add(options)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(topic)
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 4 || messageNode.size() > 6
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject
                        || !messageNode.get(3).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val options = messageNode.get(2) as ObjectNode
                val topic = messageNode.get(3).asText()
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 5) {
                    if (!messageNode.get(4).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(4) as ArrayNode
                    if (messageNode.size() >= 6) {
                        if (!messageNode.get(5).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(5) as ObjectNode
                    }
                }

                return PublishMessage(requestId, options, topic, arguments,
                        argumentsKw)
            }
        }

        companion object {
            val ID = 16
        }
    }

    /**
     * Acknowledge sent by a Broker to a Publisher for acknowledged
     * publications. [PUBLISHED, PUBLISH.Request|id, Publication|id]
     */
    class PublishedMessage(var requestId: Long, var publicationId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(publicationId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val publicationId = messageNode.get(2).asLong()

                return PublishedMessage(requestId, publicationId)
            }
        }

        companion object {
            val ID = 17
        }
    }

    /**
     * Subscribe request sent by a Subscriber to a Broker to subscribe to a
     * topic. [SUBSCRIBE, Request|id, Options|dict, Topic|uri]
     */
    class SubscribeMessage(var requestId: Long, var options: ObjectNode?, var topic: String) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (options != null)
                messageNode.add(options)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(topic)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 4
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject
                        || !messageNode.get(3).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val options = messageNode.get(2) as ObjectNode
                val topic = messageNode.get(3).asText()

                return SubscribeMessage(requestId, options, topic)
            }
        }

        companion object {
            val ID = 32
        }
    }

    /**
     * Acknowledge sent by a Broker to a Subscriber to acknowledge a
     * subscription. [SUBSCRIBED, SUBSCRIBE.Request|id, Subscription|id]
     */
    class SubscribedMessage(var requestId: Long, var subscriptionId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(subscriptionId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val subscriptionId = messageNode.get(2).asLong()

                return SubscribedMessage(requestId, subscriptionId)
            }
        }

        companion object {
            val ID = 33
        }
    }

    /**
     * Unsubscribe request sent by a Subscriber to a Broker to unsubscribe a
     * subscription. [UNSUBSCRIBE, Request|id, SUBSCRIBED.Subscription|id]
     */
    class UnsubscribeMessage(var requestId: Long, var subscriptionId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(subscriptionId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val subscriptionId = messageNode.get(2).asLong()

                return UnsubscribeMessage(requestId, subscriptionId)
            }
        }

        companion object {
            val ID = 34
        }
    }

    /**
     * Acknowledge sent by a Broker to a Subscriber to acknowledge
     * unsubscription. [UNSUBSCRIBED, UNSUBSCRIBE.Request|id]
     */
    class UnsubscribedMessage(var requestId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 2 || !messageNode.get(1).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()

                return UnsubscribedMessage(requestId)
            }
        }

        companion object {
            val ID = 35
        }
    }

    /**
     * Event dispatched by Broker to Subscribers for subscription the event was
     * matching. [EVENT, SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id,
     * Details|dict] [EVENT, SUBSCRIBED.Subscription|id,
     * PUBLISHED.Publication|id, Details|dict, PUBLISH.Arguments|list] [EVENT,
     * SUBSCRIBED.Subscription|id, PUBLISHED.Publication|id, Details|dict,
     * PUBLISH.Arguments|list, PUBLISH.ArgumentsKw|dict]
     */
    class EventMessage(var subscriptionId: Long, var publicationId: Long,
                       var details: ObjectNode?, var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(subscriptionId)
            messageNode.add(publicationId)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 4 || messageNode.size() > 6
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong()
                        || !messageNode.get(3).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val subscriptionId = messageNode.get(1).asLong()
                val publicationId = messageNode.get(2).asLong()
                val details = messageNode.get(3) as ObjectNode
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 5) {
                    if (!messageNode.get(4).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(4) as ArrayNode
                    if (messageNode.size() >= 6) {
                        if (!messageNode.get(5).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(5) as ObjectNode
                    }
                }

                return EventMessage(subscriptionId, publicationId, details,
                        arguments, argumentsKw)
            }
        }

        companion object {
            val ID = 36
        }
    }

    /**
     * Call as originally issued by the Caller to the Dealer. [CALL, Request|id,
     * Options|dict, Procedure|uri] [CALL, Request|id, Options|dict,
     * Procedure|uri, Arguments|list] [CALL, Request|id, Options|dict,
     * Procedure|uri, Arguments|list, ArgumentsKw|dict]
     */
    class CallMessage(var requestId: Long, var options: ObjectNode?, var procedure: String,
                      var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (options != null)
                messageNode.add(options)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(procedure)
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 4 || messageNode.size() > 6
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject
                        || !messageNode.get(3).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val options = messageNode.get(2) as ObjectNode
                val procedure = messageNode.get(3).asText()
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 5) {
                    if (!messageNode.get(4).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(4) as ArrayNode
                    if (messageNode.size() >= 6) {
                        if (!messageNode.get(5).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(5) as ObjectNode
                    }
                }

                return CallMessage(requestId, options, procedure,
                        arguments, argumentsKw)
            }
        }

        companion object {
            val ID = 48
        }
    }

    /**
     * Result of a call as returned by Dealer to Caller. [RESULT,
     * CALL.Request|id, Details|dict] [RESULT, CALL.Request|id, Details|dict,
     * YIELD.Arguments|list] [RESULT, CALL.Request|id, Details|dict,
     * YIELD.Arguments|list, YIELD.ArgumentsKw|dict]
     */
    class ResultMessage(var requestId: Long, var details: ObjectNode?,
                        var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 3 || messageNode.size() > 5
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val details = messageNode.get(2) as ObjectNode
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 4) {
                    if (!messageNode.get(3).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(3) as ArrayNode
                    if (messageNode.size() >= 5) {
                        if (!messageNode.get(4).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(4) as ObjectNode
                    }
                }

                return ResultMessage(requestId, details, arguments,
                        argumentsKw)
            }
        }

        companion object {
            val ID = 50
        }
    }

    /**
     * A Callees request to register an endpoint at a Dealer. [REGISTER,
     * Request|id, Options|dict, Procedure|uri]
     */
    class RegisterMessage(var requestId: Long, var options: ObjectNode?, var procedure: String) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (options != null)
                messageNode.add(options)
            else
                messageNode.add(mapper.createObjectNode())
            messageNode.add(procedure)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 4
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject
                        || !messageNode.get(3).isTextual)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val options = messageNode.get(2) as ObjectNode
                val procedure = messageNode.get(3).asText()

                return RegisterMessage(requestId, options, procedure)
            }
        }

        companion object {
            val ID = 64
        }
    }

    /**
     * Acknowledge sent by a Dealer to a Callee for successful registration.
     * [REGISTERED, REGISTER.Request|id, Registration|id]
     */
    class RegisteredMessage(var requestId: Long, var registrationId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(registrationId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val registrationId = messageNode.get(2).asLong()

                return RegisteredMessage(requestId, registrationId)
            }
        }

        companion object {
            val ID = 65
        }
    }

    /**
     * A Callees request to unregister a previsouly established registration.
     * [UNREGISTER, Request|id, REGISTERED.Registration|id]
     *
     */
    class UnregisterMessage(var requestId: Long, var registrationId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(registrationId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 3
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val registrationId = messageNode.get(2).asLong()

                return UnregisterMessage(requestId, registrationId)
            }
        }

        companion object {
            val ID = 66
        }
    }

    /**
     * Acknowledge sent by a Dealer to a Callee for successful unregistration.
     * [UNREGISTERED, UNREGISTER.Request|id]
     */
    class UnregisteredMessage(var requestId: Long) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() != 2 || !messageNode.get(1).canConvertToLong())
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()

                return UnregisteredMessage(requestId)
            }
        }

        companion object {
            val ID = 67
        }
    }

    /**
     * Actual invocation of an endpoint sent by Dealer to a Callee. [INVOCATION,
     * Request|id, REGISTERED.Registration|id, Details|dict] [INVOCATION,
     * Request|id, REGISTERED.Registration|id, Details|dict,
     * CALL.Arguments|list] [INVOCATION, Request|id, REGISTERED.Registration|id,
     * Details|dict, CALL.Arguments|list, CALL.ArgumentsKw|dict]
     */
    class InvocationMessage(var requestId: Long, var registrationId: Long,
                            var details: ObjectNode?, var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            messageNode.add(registrationId)
            if (details != null)
                messageNode.add(details)
            else
                messageNode.add(mapper.createObjectNode())
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 4 || messageNode.size() > 6
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).canConvertToLong()
                        || !messageNode.get(3).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val registrationId = messageNode.get(2).asLong()
                val details = messageNode.get(3) as ObjectNode
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 5) {
                    if (!messageNode.get(4).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(4) as ArrayNode
                    if (messageNode.size() >= 6) {
                        if (!messageNode.get(5).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(5) as ObjectNode
                    }
                }

                return InvocationMessage(requestId, registrationId,
                        details, arguments, argumentsKw)
            }
        }

        companion object {
            val ID = 68
        }
    }

    /**
     * Actual yield from an endpoint send by a Callee to Dealer. [YIELD,
     * INVOCATION.Request|id, Options|dict] [YIELD, INVOCATION.Request|id,
     * Options|dict, Arguments|list] [YIELD, INVOCATION.Request|id,
     * Options|dict, Arguments|list, ArgumentsKw|dict]
     */
    class YieldMessage(var requestId: Long, var options: ObjectNode?,
                       var arguments: ArrayNode?, var argumentsKw: ObjectNode?) : WampMessage() {

        @Throws(WampError::class)
        override fun toObjectArray(mapper: ObjectMapper): JsonNode {
            val messageNode = mapper.createArrayNode()
            messageNode.add(ID)
            messageNode.add(requestId)
            if (options != null)
                messageNode.add(options)
            else
                messageNode.add(mapper.createObjectNode())
            if (arguments != null)
                messageNode.add(arguments)
            else if (argumentsKw != null)
                messageNode.add(mapper.createArrayNode())
            if (argumentsKw != null)
                messageNode.add(argumentsKw)
            return messageNode
        }

        internal class Factory : WampMessageFactory {
            @Throws(WampError::class)
            override fun fromObjectArray(messageNode: ArrayNode): WampMessage {
                if (messageNode.size() < 3 || messageNode.size() > 5
                        || !messageNode.get(1).canConvertToLong()
                        || !messageNode.get(2).isObject)
                    throw WampError(ApplicationError.INVALID_MESSAGE)

                val requestId = messageNode.get(1).asLong()
                val options = messageNode.get(2) as ObjectNode
                var arguments: ArrayNode? = null
                var argumentsKw: ObjectNode? = null

                if (messageNode.size() >= 4) {
                    if (!messageNode.get(3).isArray)
                        throw WampError(ApplicationError.INVALID_MESSAGE)
                    arguments = messageNode.get(3) as ArrayNode
                    if (messageNode.size() >= 5) {
                        if (!messageNode.get(4).isObject)
                            throw WampError(ApplicationError.INVALID_MESSAGE)
                        argumentsKw = messageNode.get(4) as ObjectNode
                    }
                }

                return YieldMessage(requestId, options, arguments,
                        argumentsKw)
            }
        }

        companion object {
            val ID = 70
        }
    }
}
