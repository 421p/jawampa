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
import com.fasterxml.jackson.databind.node.ObjectNode
import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.AsyncSubject
import com.github.sshaddicts.jawampa.WampMessages.*
import com.github.sshaddicts.jawampa.connection.*
import com.github.sshaddicts.jawampa.internal.*
import com.github.sshaddicts.jawampa.connection.*
import com.github.sshaddicts.jawampa.internal.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService

/**
 * The [WampRouter] provides Dealer and Broker functionality for the WAMP
 * protocol.<br></br>
 */
class WampRouter internal constructor(realms: Map<String, RealmConfig>) {

    /** Represents a realm that is exposed through the router  */
    internal class Realm(val config: RealmConfig) {
        val welcomeDetails: ObjectNode
        val channelsBySessionId: MutableMap<Long, ClientHandler> = HashMap()
        val procedures: MutableMap<String, Procedure> = HashMap()

        // Fields that are used for implementing subscription functionality
        val subscriptionsByFlags = EnumMap<SubscriptionFlags, MutableMap<String, Subscription>>(SubscriptionFlags::class.java)
        val subscriptionsById: MutableMap<Long, Subscription> = HashMap()
        var lastUsedSubscriptionId = IdValidator.MIN_VALID_ID

        init {
            subscriptionsByFlags.put(SubscriptionFlags.Exact, HashMap<String, Subscription>())
            subscriptionsByFlags.put(SubscriptionFlags.Prefix, HashMap<String, Subscription>())
            subscriptionsByFlags.put(SubscriptionFlags.Wildcard, HashMap<String, Subscription>())

            // Expose the roles that are configured for the realm
            val objectMapper = ObjectMapper()
            welcomeDetails = objectMapper.createObjectNode()
            welcomeDetails.put("agent", Version.version)
            val routerRoles = welcomeDetails.putObject("roles")
            for (role in config.roles) {
                val roleNode = routerRoles.putObject(role.toString())
                if (role == WampRoles.Publisher) {
                    val featuresNode = roleNode.putObject("features")
                    featuresNode.put("publisher_exclusion", true)
                } else if (role == WampRoles.Subscriber) {
                    val featuresNode = roleNode.putObject("features")
                    featuresNode.put("pattern_based_subscription", true)
                }
            }
        }

        fun includeChannel(channel: ClientHandler, sessionId: Long, roles: MutableSet<WampRoles>) {
            channel.realm = this
            channel.sessionId = sessionId
            channel.roles = roles
            channelsBySessionId.put(sessionId, channel)
        }

        fun removeChannel(channel: ClientHandler, removeFromList: Boolean) {
            if (channel.realm == null) return

            if (channel.subscriptionsById != null) {
                // Remove the channels subscriptions from our subscription table
                for (sub in channel.subscriptionsById!!.values) {
                    sub.subscribers.remove(channel)
                    if (sub.subscribers.isEmpty()) {
                        // Subscription is no longer used by any client
                        subscriptionsByFlags[sub.flags]!!.remove(sub.topic)
                        subscriptionsById.remove(sub.subscriptionId)
                    }
                }
                channel.subscriptionsById!!.clear()
                channel.subscriptionsById = null
            }

            if (channel.providedProcedures != null) {
                // Remove the clients procedures from our procedure table
                for (proc in channel.providedProcedures!!.values) {
                    // Clear all pending invocations and thereby inform other clients
                    // that the proc has gone away
                    for (invoc in proc.pendingCalls) {
                        if (invoc.caller!!.state != RouterHandlerState.Open) continue
                        val errMsg = ErrorMessage(CallMessage.ID, invoc.callRequestId, null, ApplicationError.NO_SUCH_PROCEDURE, null, null)
                        invoc.caller!!.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                    }
                    proc.pendingCalls.clear()
                    // Remove the procedure from the realm
                    procedures.remove(proc.procName)
                }
                channel.providedProcedures = null
                channel.pendingInvocations = null
            }

            if (removeFromList) {
                channelsBySessionId.remove(channel.sessionId)
            }
            channel.realm = null
            channel.roles!!.clear()
            channel.roles = null
            channel.sessionId = 0
        }
    }

    internal class Procedure(val procName: String, val provider: ClientHandler, val registrationId: Long) {
        val pendingCalls: MutableList<Invocation> = ArrayList()
    }

    internal class Invocation {
        var procedure: Procedure? = null
        var callRequestId: Long = 0
        var caller: ClientHandler? = null
        var invocationRequestId: Long = 0
    }

    internal class Subscription(val topic: String, val flags: SubscriptionFlags, val subscriptionId: Long) {
        val components: Array<String>? // non-null only for wildcard type
        val subscribers: MutableSet<ClientHandler>

        init {
            this.components = if (flags == SubscriptionFlags.Wildcard) topic.split("\\.".toRegex()).toTypedArray() else null
            this.subscribers = HashSet()
        }
    }

    internal val eventLoop: ScheduledExecutorService
    internal val scheduler: Scheduler

    internal val objectMapper = ObjectMapper()

    internal var isDisposed = false
    internal var closedFuture = AsyncSubject.create<Void>()

    internal val realms: MutableMap<String, Realm>
    internal val idleChannels: MutableSet<IConnectionController>

    /** The number of connections that have to be closed. This is important for shutdown  */
    internal var connectionsToClose = 0

    /**
     * Returns the (singlethreaded) EventLoop on which this router is running.<br></br>
     * This is required by other Netty ChannelHandlers that want to forward messages
     * to the router.
     */
    fun eventLoop(): ScheduledExecutorService {
        return eventLoop
    }

    /**
     * Returns the Jackson [ObjectMapper] that is used for JSON serialization,
     * deserialization and object mapping by this router.
     */
    fun objectMapper(): ObjectMapper {
        return objectMapper
    }

    init {

        // Populate the realms from the configuration
        this.realms = HashMap()
        for ((key, value) in realms) {
            val info = Realm(value)
            this.realms.put(key, info)
        }

        // Create an eventloop and the RX scheduler on top of it
        this.eventLoop = Executors.newSingleThreadScheduledExecutor { r ->
            val t = Thread(r, "WampRouterEventLoop")
            t.isDaemon = true
            t
        }
        this.scheduler = Schedulers.from(eventLoop)

        idleChannels = HashSet()
    }

    /**
     * Tries to schedule a runnable on the underlying executor.<br></br>
     * Rejected executions will be suppressed.<br></br>
     * This is useful for cases when the clients EventLoop is shut down before
     * the EventLoop of the underlying connection.
     *
     * @param action The action to schedule.
     */
    internal fun tryScheduleAction(action: Runnable) {
        try {
            eventLoop.submit(action)
        } catch (e: RejectedExecutionException) {
        }

    }

    private val onConnectionClosed = object : ICompletionCallback<Void> {
        override fun onCompletion(future: IWampConnectionFuture<Void>) {
            tryScheduleAction(Runnable {
                connectionsToClose -= 1
                if (isDisposed && connectionsToClose == 0) {
                    eventLoop.shutdown()
                    closedFuture.onNext(null)
                    closedFuture.onCompleted()
                }
            })
        }

    }

    /**
     * Increases the number of connections to close and starts to asynchronously
     * close it. When this has happened [WampRouter.onConnectionClosed] will be called.
     */
    private fun closeConnection(controller: IConnectionController?, sendRemaining: Boolean) {
        connectionsToClose += 1
        val promise = WampConnectionPromise<Void>(onConnectionClosed, null)
        controller!!.close(sendRemaining, promise)
    }

    /**
     * Closes the router.<br></br>
     * This will shut down all realm that are registered to the router.
     * All connections to clients on the realm will be closed.<br></br>
     * However pending calls will be completed through an error message
     * as far as possible.
     * @return Returns an observable that completes when the router is completely shut down.
     */
    fun close(): Observable<Void> {
        if (eventLoop.isShutdown) return closedFuture

        tryScheduleAction(Runnable {
            if (isDisposed) return@Runnable
            isDisposed = true

            // Close all currently connected channels
            for (con in idleChannels) closeConnection(con, true)
            idleChannels.clear()

            for (ri in realms.values) {
                for (channel in ri.channelsBySessionId.values) {
                    ri.removeChannel(channel, false)
                    channel.markAsClosed()
                    val goodbye = GoodbyeMessage(null, ApplicationError.SYSTEM_SHUTDOWN)
                    channel.controller!!.sendMessage(goodbye, IWampConnectionPromise.Empty)
                    closeConnection(channel.controller, true)
                }
                ri.channelsBySessionId.clear()
            }

            // close is asynchronous. It will wait until all connections are closed
            // Afterwards the eventLoop will be shutDown.
        })

        return closedFuture
    }

    internal enum class RouterHandlerState {
        Open,
        Closed
    }

    internal var connectionAcceptor: IWampConnectionAcceptor = object : IWampConnectionAcceptor {
        override fun createNewConnectionListener(): IWampConnectionListener {
            val newHandler = ClientHandler()
            val newController = QueueingConnectionController(eventLoop, newHandler)
            newHandler.controller = newController
            return newController
        }

        override fun acceptNewConnection(newConnection: IWampConnection?,
                                         connectionListener: IWampConnectionListener?) {
            try {
                eventLoop.execute(Runnable {
                    if (connectionListener == null
                            || connectionListener !is QueueingConnectionController
                            || newConnection == null) {
                        // This is always true if the transport provider does not manipulate the structure
                        // that was sent by the router
                        newConnection?.close(false, IWampConnectionPromise.Empty)
                        return@Runnable
                    }
                    val controller = connectionListener as QueueingConnectionController?
                    controller!!.setConnection(newConnection)

                    if (isDisposed) {
                        // Got an incoming connection after the router has already shut down.
                        // Therefore we close the connection
                        closeConnection(controller, false)
                    } else {
                        // Store the controller
                        idleChannels.add(controller)
                    }
                })
            } catch (e: RejectedExecutionException) {
                // Close the connection
                // Defer the operation to avoid a cyclic call from the new connection
                // to this method and back
                val r = Runnable { newConnection!!.close(false, IWampConnectionPromise.Empty) }
                val executor = Executors.newSingleThreadExecutor()
                executor.submit(r)
                executor.shutdown()
            }

        }
    }

    /**
     * Returns the [IWampConnectionAcceptor] interface that the router
     * provides in order to be able to accept new connection.
     */
    fun connectionAcceptor(): IWampConnectionAcceptor {
        return connectionAcceptor
    }

    internal inner class ClientHandler : IWampConnectionListener {

        var controller: IConnectionController? = null
        var state = RouterHandlerState.Open
        var sessionId: Long = 0
        var realm: Realm? = null
        var roles: MutableSet<WampRoles>? = null

        /**
         * Procedures that this channel provides.<br></br>
         * Key is the registration ID, Value is the procedure
         */
        var providedProcedures: MutableMap<Long, Procedure>? = null

        var pendingInvocations: MutableMap<Long, Invocation>? = null

        /** The Set of subscriptions to which this channel is subscribed  */
        var subscriptionsById: MutableMap<Long, Subscription>? = null

        var lastUsedId = IdValidator.MIN_VALID_ID

        fun markAsClosed() {
            state = RouterHandlerState.Closed
        }

        override fun transportClosed() {
            // Handle in the same way as a close due to an error
            transportError(null)
        }

        override fun transportError(cause: Throwable?) {
            if (isDisposed || state != RouterHandlerState.Open) return
            if (realm != null) {
                closeActiveClient(this@ClientHandler, null)
            } else {
                closePassiveClient(this@ClientHandler)
            }
        }

        override fun messageReceived(message: WampMessage) {
            if (isDisposed || state != RouterHandlerState.Open) return
            if (realm == null) {
                onMessageFromUnregisteredChannel(this@ClientHandler, message)
            } else {
                onMessageFromRegisteredChannel(this@ClientHandler, message)
            }
        }
    }

    private fun onMessageFromRegisteredChannel(handler: ClientHandler, msg: WampMessage) {

        // TODO: Validate roles for all relevant messages

        if (msg is HelloMessage || msg is WelcomeMessage) {
            // The client sent hello but it was already registered -> This is an error
            // If the client sends welcome it's also an error
            closeActiveClient(handler, GoodbyeMessage(null, ApplicationError.INVALID_ARGUMENT))
        } else if (msg is AbortMessage || msg is GoodbyeMessage) {
            // The client wants to leave the realm
            // Remove the channel from the realm
            handler.realm!!.removeChannel(handler, true)
            // But add it to the list of passive channels
            idleChannels.add(handler.controller!!)
            // Echo the message in case of goodbye
            if (msg is GoodbyeMessage) {
                val reply = GoodbyeMessage(null, ApplicationError.GOODBYE_AND_OUT)
                handler.controller!!.sendMessage(reply, IWampConnectionPromise.Empty)
            }
        } else if (msg is CallMessage) {
            // The client wants to call a remote function
            // Verify the message
            var err: String? = null
            if (!UriValidator.tryValidate(msg.procedure, handler.realm!!.config.useStrictUriValidation)) {
                // Client sent an invalid URI
                err = ApplicationError.INVALID_URI
            }

            if (err == null && !IdValidator.isValidId(msg.requestId)) {
                // Client sent an invalid request ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            var proc: Procedure? = null
            if (err == null) {
                proc = handler.realm!!.procedures[msg.procedure]
                if (proc == null) err = ApplicationError.NO_SUCH_PROCEDURE
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(CallMessage.ID, msg.requestId, null, err, null, null)
                handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                return
            }

            // Everything checked, we can forward the call to the provider
            val invoc = Invocation()
            invoc.callRequestId = msg.requestId
            invoc.caller = handler
            invoc.procedure = proc
            invoc.invocationRequestId = IdGenerator.newLinearId(proc!!.provider.lastUsedId,
                    proc.provider.pendingInvocations)
            proc.provider.lastUsedId = invoc.invocationRequestId

            // Store the invocation
            proc.provider.pendingInvocations!!.put(invoc.invocationRequestId, invoc)
            // Store the call in the procedure to return error if client unregisters
            proc.pendingCalls.add(invoc)

            // And send it to the provider
            val imsg = InvocationMessage(invoc.invocationRequestId,
                    proc.registrationId, null, msg.arguments, msg.argumentsKw)
            proc.provider.controller!!.sendMessage(imsg, IWampConnectionPromise.Empty)
        } else if (msg is YieldMessage) {
            // The clients sends as the result of an RPC
            // Verify the message
            if (!IdValidator.isValidId(msg.requestId)) return
            // Look up the invocation to find the original caller
            if (handler.pendingInvocations == null) return   // If a client send a yield without an invocation, return
            val invoc = handler.pendingInvocations!![msg.requestId] ?: return
// There is no invocation pending under this ID
            handler.pendingInvocations!!.remove(msg.requestId)
            invoc.procedure!!.pendingCalls.remove(invoc)
            // Send the result to the original caller
            val result = ResultMessage(invoc.callRequestId, null, msg.arguments, msg.argumentsKw)
            invoc.caller!!.controller!!.sendMessage(result, IWampConnectionPromise.Empty)
        } else if (msg is ErrorMessage) {
            if (!IdValidator.isValidId(msg.requestId)) {
                return
            }
            if (msg.requestType == InvocationMessage.ID) {
                if (!UriValidator.tryValidate(msg.error, handler.realm!!.config.useStrictUriValidation)) {
                    // The Message provider has sent us an invalid URI for the error string
                    // We better don't forward it but instead close the connection, which will
                    // give the original caller an unknown message error
                    closeActiveClient(handler, GoodbyeMessage(null, ApplicationError.INVALID_ARGUMENT))
                    return
                }

                // Look up the invocation to find the original caller
                if (handler.pendingInvocations == null) return  // if an error is send before an invocation, do not do anything
                val invoc = handler.pendingInvocations!![msg.requestId] ?: return
// There is no invocation pending under this ID
                handler.pendingInvocations!!.remove(msg.requestId)
                invoc.procedure!!.pendingCalls.remove(invoc)

                // Send the result to the original caller
                val fwdError = ErrorMessage(CallMessage.ID, invoc.callRequestId, null, msg.error, msg.arguments, msg.argumentsKw)
                invoc.caller!!.controller!!.sendMessage(fwdError, IWampConnectionPromise.Empty)
            }
            // else TODO: Are there any other possibilities where a client could return ERROR
        } else if (msg is RegisterMessage) {
            // The client wants to register a procedure
            // Verify the message
            var err: String? = null
            if (!UriValidator.tryValidate(msg.procedure, handler.realm!!.config.useStrictUriValidation)) {
                // Client sent an invalid URI
                err = ApplicationError.INVALID_URI
            }

            if (err == null && !IdValidator.isValidId(msg.requestId)) {
                // Client sent an invalid request ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            var proc: Procedure? = null
            if (err == null) {
                proc = handler.realm!!.procedures[msg.procedure]
                if (proc != null) err = ApplicationError.PROCEDURE_ALREADY_EXISTS
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(RegisterMessage.ID, msg.requestId, null, err, null, null)
                handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                return
            }

            // Everything checked, we can register the caller as the procedure provider
            val registrationId = IdGenerator.newLinearId(handler.lastUsedId, handler.providedProcedures)
            handler.lastUsedId = registrationId
            val procInfo = Procedure(msg.procedure, handler, registrationId)

            // Insert new procedure
            handler.realm!!.procedures.put(msg.procedure, procInfo)
            if (handler.providedProcedures == null) {
                handler.providedProcedures = HashMap()
                handler.pendingInvocations = HashMap()
            }
            handler.providedProcedures!!.put(procInfo.registrationId, procInfo)

            val response = RegisteredMessage(msg.requestId, procInfo.registrationId)
            handler.controller!!.sendMessage(response, IWampConnectionPromise.Empty)
        } else if (msg is UnregisterMessage) {
            // The client wants to unregister a procedure
            // Verify the message
            var err: String? = null
            if (!IdValidator.isValidId(msg.requestId) || !IdValidator.isValidId(msg.registrationId)) {
                // Client sent an invalid request or registration ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            var proc: Procedure? = null
            if (err == null) {
                if (handler.providedProcedures != null) {
                    proc = handler.providedProcedures!![msg.registrationId]
                }
                // Check whether the procedure exists AND if the caller is the owner
                // If the caller is not the owner it might be an attack, so we don't
                // disclose that the procedure exists.
                if (proc == null) {
                    err = ApplicationError.NO_SUCH_REGISTRATION
                }
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(UnregisterMessage.ID, msg.requestId, null, err, null, null)
                handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                return
            }

            // Mark pending calls to this procedure as failed
            for (invoc in proc!!.pendingCalls) {
                handler.pendingInvocations!!.remove(invoc.invocationRequestId)
                if (invoc.caller!!.state == RouterHandlerState.Open) {
                    val errMsg = ErrorMessage(CallMessage.ID, invoc.callRequestId, null, ApplicationError.NO_SUCH_PROCEDURE, null, null)
                    invoc.caller!!.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                }
            }
            proc.pendingCalls.clear()

            // Remove the procedure from the realm and the handler
            handler.realm!!.procedures.remove(proc.procName)
            handler.providedProcedures!!.remove(proc.registrationId)

            if (handler.providedProcedures!!.size == 0) {
                handler.providedProcedures = null
                handler.pendingInvocations = null
            }

            // Send the acknowledge
            val response = UnregisteredMessage(msg.requestId)
            handler.controller!!.sendMessage(response, IWampConnectionPromise.Empty)
        } else if (msg is SubscribeMessage) {
            // The client wants to subscribe to a procedure
            // Verify the message
            var err: String? = null

            // Find subscription match type
            var flags = SubscriptionFlags.Exact
            if (msg.options != null) {
                val match = msg.options!!.get("match")
                if (match != null) {
                    val matchValue = match.asText()
                    if ("prefix" == matchValue) {
                        flags = SubscriptionFlags.Prefix
                    } else if ("wildcard" == matchValue) {
                        flags = SubscriptionFlags.Wildcard
                    }
                }
            }

            if (flags == SubscriptionFlags.Exact) {
                if (!UriValidator.tryValidate(msg.topic, handler.realm!!.config.useStrictUriValidation)) {
                    // Client sent an invalid URI
                    err = ApplicationError.INVALID_URI
                }
            } else if (flags == SubscriptionFlags.Prefix) {
                if (!UriValidator.tryValidatePrefix(msg.topic, handler.realm!!.config.useStrictUriValidation)) {
                    // Client sent an invalid URI
                    err = ApplicationError.INVALID_URI
                }
            } else if (flags == SubscriptionFlags.Wildcard) {
                if (!UriValidator.tryValidateWildcard(msg.topic, handler.realm!!.config.useStrictUriValidation)) {
                    // Client sent an invalid URI
                    err = ApplicationError.INVALID_URI
                }
            }

            if (err == null && !IdValidator.isValidId(msg.requestId)) {
                // Client sent an invalid request ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(SubscribeMessage.ID, msg.requestId, null, err, null, null)
                handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                return
            }

            // Create a new subscription map for the client if it was not subscribed before
            if (handler.subscriptionsById == null) {
                handler.subscriptionsById = HashMap()
            }

            // Search if a subscription from any client on the realm to this topic exists
            val subscriptionMap = handler.realm!!.subscriptionsByFlags.get(flags)
            var subscription: Subscription? = subscriptionMap!![msg.topic]
            if (subscription == null) {
                // No client was subscribed to this URI up to now
                // Create a new subscription id
                val subscriptionId = IdGenerator.newLinearId(handler.realm!!.lastUsedSubscriptionId,
                        handler.realm!!.subscriptionsById)
                handler.realm!!.lastUsedSubscriptionId = subscriptionId
                // Create and add the new subscription
                subscription = Subscription(msg.topic, flags, subscriptionId)
                subscriptionMap!!.put(msg.topic, subscription)
                handler.realm!!.subscriptionsById.put(subscriptionId, subscription)
            }

            // We check if the client is already subscribed to this topic by trying to add the
            // new client as a receiver. If the client is already a receiver we do nothing
            // (already subscribed and already stored in handler.subscriptionsById). Calling
            // add to check and add is more efficient than checking with contains first.
            // If the client was already subscribed this will return the same subscriptionId
            // than as for the last subscription.
            // See discussion in https://groups.google.com/forum/#!topic/wampws/kC878Ngc9Z0
            if (subscription.subscribers.add(handler)) {
                // Add the subscription on the client
                handler.subscriptionsById!!.put(subscription.subscriptionId, subscription)
            }

            val response = SubscribedMessage(msg.requestId, subscription.subscriptionId)
            handler.controller!!.sendMessage(response, IWampConnectionPromise.Empty)
        } else if (msg is UnsubscribeMessage) {
            // The client wants to cancel a subscription
            // Verify the message
            var err: String? = null
            if (!IdValidator.isValidId(msg.requestId) || !IdValidator.isValidId(msg.subscriptionId)) {
                // Client sent an invalid request or registration ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            var s: Subscription? = null
            if (err == null) {
                // Check whether such a subscription exists and fetch the topic name
                if (handler.subscriptionsById != null) {
                    s = handler.subscriptionsById!![msg.subscriptionId]
                }
                if (s == null) {
                    err = ApplicationError.NO_SUCH_SUBSCRIPTION
                }
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(UnsubscribeMessage.ID, msg.requestId, null, err, null, null)
                handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                return
            }

            // Remove the channel as an receiver from the subscription
            s!!.subscribers.remove(handler)

            // Remove the subscription from the handler
            handler.subscriptionsById!!.remove(s.subscriptionId)
            if (handler.subscriptionsById!!.isEmpty()) {
                handler.subscriptionsById = null
            }

            // Remove the subscription from the realm if no subscriber is left
            if (s.subscribers.isEmpty()) {
                handler.realm!!.subscriptionsByFlags[s.flags]!!.remove(s.topic)
                handler.realm!!.subscriptionsById.remove(s.subscriptionId)
            }

            // Send the acknowledge
            val response = UnsubscribedMessage(msg.requestId)
            handler.controller!!.sendMessage(response, IWampConnectionPromise.Empty)
        } else if (msg is PublishMessage) {
            // The client wants to publish something to all subscribers (apart from himself)
            // Check whether the client wants an acknowledgement for the publication
            // Default is no
            var sendAcknowledge = false
            val ackOption = msg.options!!.get("acknowledge")
            if (ackOption != null && ackOption.asBoolean())
                sendAcknowledge = true

            var err: String? = null
            if (!UriValidator.tryValidate(msg.topic, handler.realm!!.config.useStrictUriValidation)) {
                // Client sent an invalid URI
                err = ApplicationError.INVALID_URI
            }

            if (err == null && !IdValidator.isValidId(msg.requestId)) {
                // Client sent an invalid request ID
                err = ApplicationError.INVALID_ARGUMENT
            }

            if (err != null) { // If we have an error send that to the client
                val errMsg = ErrorMessage(PublishMessage.ID, msg.requestId, null, err, null, null)
                if (sendAcknowledge) {
                    handler.controller!!.sendMessage(errMsg, IWampConnectionPromise.Empty)
                }
                return
            }

            val publicationId = IdGenerator.newRandomId(null) // Store that somewhere?

            // Get the subscriptions for this topic on the realm
            val exactSubscription = handler.realm!!
                    .subscriptionsByFlags[SubscriptionFlags.Exact]!![msg.topic]
            if (exactSubscription != null) {
                publishEvent(handler, msg, publicationId, exactSubscription)
            }

            val prefixSubscriptionMap = handler.realm!!.subscriptionsByFlags.get(SubscriptionFlags.Prefix)

            prefixSubscriptionMap!!.values
                    .filter { msg.topic.startsWith(it.topic) }
                    .forEach { publishEvent(handler, msg, publicationId, it) }

            val wildcardSubscriptionMap = handler.realm!!.subscriptionsByFlags[SubscriptionFlags.Wildcard]
            val components = msg.topic.split("\\.".toRegex()).toTypedArray()
            for (wildcardSubscription in wildcardSubscriptionMap!!.values) {
                var matched = true
                if (components.size == wildcardSubscription.components!!.size) {
                    for (i in components.indices) {
                        if (wildcardSubscription.components[i].isNotEmpty() && components[i] != wildcardSubscription.components[i]) {
                            matched = false
                            break
                        }
                    }
                } else
                    matched = false

                if (matched) {
                    publishEvent(handler, msg, publicationId, wildcardSubscription)
                }
            }

            if (sendAcknowledge) {
                val response = PublishedMessage(msg.requestId, publicationId)
                handler.controller!!.sendMessage(response, IWampConnectionPromise.Empty)
            }
        }
    }

    private fun publishEvent(publisher: ClientHandler, pub: PublishMessage, publicationId: Long, subscription: Subscription) {
        var details: ObjectNode? = null
        if (subscription.flags != SubscriptionFlags.Exact) {
            details = objectMapper.createObjectNode()
            details!!.put("topic", pub.topic)
        }

        val ev = EventMessage(subscription.subscriptionId, publicationId,
                details, pub.arguments, pub.argumentsKw)

        for (receiver in subscription.subscribers) {
            if (receiver === publisher) { // Potentially skip the publisher
                var skipPublisher = true
                if (pub.options != null) {
                    val excludeMeNode = pub.options!!.get("exclude_me")
                    if (excludeMeNode != null) {
                        skipPublisher = excludeMeNode.asBoolean(true)
                    }
                }
                if (skipPublisher) continue
            }

            // Publish the event to the subscriber
            receiver.controller!!.sendMessage(ev, IWampConnectionPromise.Empty)
        }
    }

    private fun onMessageFromUnregisteredChannel(channelHandler: ClientHandler, msg: WampMessage) {
        // Only HELLO is allowed when a channel is not registered
        if (msg !is HelloMessage) {
            // Close the connection
            closePassiveClient(channelHandler)
            return
        }

        var errorMsg: String? = null
        var realm: Realm? = null
        if (!UriValidator.tryValidate(msg.realm, false)) {
            errorMsg = ApplicationError.INVALID_URI
        } else {
            realm = realms[msg.realm]
            if (realm == null) {
                errorMsg = ApplicationError.NO_SUCH_REALM
            }
        }

        if (errorMsg != null) {
            val abort = AbortMessage(null, errorMsg)
            channelHandler.controller!!.sendMessage(abort, IWampConnectionPromise.Empty)
            return
        }

        val roles = HashSet<WampRoles>()
        var hasUnsupportedRoles = false

        val n = msg.details!!.get("roles")
        if (n != null && n.isObject) {
            val rolesNode = n as ObjectNode
            val roleKeys = rolesNode.fieldNames()
            while (roleKeys.hasNext()) {
                val role = WampRoles.fromString(roleKeys.next())
                if (!SUPPORTED_CLIENT_ROLES.contains(role)) hasUnsupportedRoles = true
                if (role != null) roles.add(role)
            }
        }

        if (roles.size == 0 || hasUnsupportedRoles) {
            val abort = AbortMessage(null, ApplicationError.NO_SUCH_ROLE)
            channelHandler.controller!!.sendMessage(abort, IWampConnectionPromise.Empty)
            return
        }

        val sessionId = IdGenerator.newRandomId(realm!!.channelsBySessionId)

        // Include the channel into the realm
        realm.includeChannel(channelHandler, sessionId, roles)
        // Remove the channel from the idle channel list - It is no longer idle
        idleChannels.remove(channelHandler.controller)

        // Respond with the WELCOME message
        val welcome = WelcomeMessage(channelHandler.sessionId, realm.welcomeDetails)
        channelHandler.controller!!.sendMessage(welcome, IWampConnectionPromise.Empty)
    }

    private fun closeActiveClient(channel: ClientHandler?, closeMessage: WampMessage?) {
        if (channel == null) return

        channel.realm!!.removeChannel(channel, true)
        channel.markAsClosed()

        if (channel.controller != null) {
            if (closeMessage != null)
                channel.controller!!.sendMessage(closeMessage, IWampConnectionPromise.Empty)
            closeConnection(channel.controller, true)
        }
    }

    private fun closePassiveClient(channelHandler: ClientHandler) {
        idleChannels.remove(channelHandler.controller)
        channelHandler.markAsClosed()
        closeConnection(channelHandler.controller, false)
    }

    companion object {

        internal val SUPPORTED_CLIENT_ROLES: MutableSet<WampRoles>

        init {
            SUPPORTED_CLIENT_ROLES = HashSet()
            SUPPORTED_CLIENT_ROLES.add(WampRoles.Caller)
            SUPPORTED_CLIENT_ROLES.add(WampRoles.Callee)
            SUPPORTED_CLIENT_ROLES.add(WampRoles.Publisher)
            SUPPORTED_CLIENT_ROLES.add(WampRoles.Subscriber)
        }
    }
}
