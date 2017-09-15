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

package com.github.sshaddicts.jawampa.client

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.sshaddicts.jawampa.*
import rx.Subscriber
import rx.functions.Action1
import rx.subjects.AsyncSubject
import rx.subscriptions.Subscriptions
import com.github.sshaddicts.jawampa.*
import com.github.sshaddicts.jawampa.WampMessages.*
import com.github.sshaddicts.jawampa.connection.IConnectionController
import com.github.sshaddicts.jawampa.connection.IWampConnectionPromise
import com.github.sshaddicts.jawampa.internal.IdGenerator
import com.github.sshaddicts.jawampa.internal.IdValidator
import java.util.*

/**
 * The client is connected to the router and the session was established
 */
class SessionEstablishedState(private val stateController: StateController,
                              /** The currently active connection  */
                              internal var connectionController: IConnectionController,
                              internal val sessionId: Long, internal val welcomeDetails: ObjectNode, internal val routerRoles: EnumSet<WampRoles>) : ClientState {

    internal enum class PubSubState {
        Subscribing,
        Subscribed,
        Unsubscribing,
        Unsubscribed
    }

    internal enum class RegistrationState {
        Registering,
        Registered,
        Unregistering,
        Unregistered
    }

    internal class RequestMapEntry(val requestType: Int, val resultSubject: AsyncSubject<*>)

    internal class SubscriptionMapEntry(val flags: SubscriptionFlags, var state: PubSubState) {
        var subscriptionId: Long = 0

        val subscribers: MutableList<Subscriber<in PubSubData>> = ArrayList()
    }

    internal class RegisteredProceduresMapEntry(val subscriber: Subscriber<in Request>, var state: RegistrationState) {
        var registrationId: Long = 0
    }

    private var requestMap = HashMap<Long, RequestMapEntry>()

    private var subscriptionsByFlags = EnumMap<SubscriptionFlags, HashMap<String, SubscriptionMapEntry>>(SubscriptionFlags::class.java)
    private var subscriptionsBySubscriptionId = HashMap<Long, SubscriptionMapEntry>()

    private var registeredProceduresByUri = HashMap<String, RegisteredProceduresMapEntry>()
    private var registeredProceduresById = HashMap<Long, RegisteredProceduresMapEntry>()

    private var lastRequestId = IdValidator.MIN_VALID_ID

    init {

        subscriptionsByFlags.put(SubscriptionFlags.Exact, HashMap<String, SubscriptionMapEntry>())
        subscriptionsByFlags.put(SubscriptionFlags.Prefix, HashMap<String, SubscriptionMapEntry>())
        subscriptionsByFlags.put(SubscriptionFlags.Wildcard, HashMap<String, SubscriptionMapEntry>())
    }

    fun connectionController(): IConnectionController {
        return connectionController
    }

    override fun onEnter(lastState: ClientState) {
        stateController.setExternalState(WampClient.ConnectedState(sessionId, welcomeDetails, routerRoles))
    }

    override fun onLeave(newState: ClientState) {

    }

    override fun initClose() {
        closeSession(null, ApplicationError.SYSTEM_SHUTDOWN, false)
    }

    internal fun closeSession(disconnectReason: Throwable?, optCloseMessageReason: String?, reconnectAllowed: Boolean) {
        // Send goodbye message with close reason to the remote
        if (optCloseMessageReason != null) {
            val msg = GoodbyeMessage(null, optCloseMessageReason)
            connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
        }

        stateController.setExternalState(WampClient.DisconnectedState(disconnectReason))

        val nrReconnectAttempts = if (reconnectAllowed) stateController.clientConfig().totalNrReconnects() else 0
        if (nrReconnectAttempts != 0) {
            stateController.setExternalState(WampClient.ConnectingState())
        }

        clearSessionData()

        val newState = WaitingForDisconnectState(stateController, nrReconnectAttempts)
        connectionController.close(true, newState.closePromise())
        stateController.setState(newState)
    }

    internal fun clearSessionData() {
        clearPendingRequests(ApplicationError(ApplicationError.TRANSPORT_CLOSED))
        clearAllSubscriptions(null)
        clearAllRegisteredProcedures(null)
    }

    internal fun clearPendingRequests(e: Throwable) {
        for ((_, value) in requestMap) {
            value.resultSubject.onError(e)
        }
        requestMap.clear()
    }

    internal fun clearAllSubscriptions(e: Throwable?) {
        for (subscriptionByUri in subscriptionsByFlags.values) {
            for (entry in subscriptionByUri.entries) {
                for (s in entry.value.subscribers) {
                    if (e == null)
                        s.onCompleted()
                    else
                        s.onError(e)
                }
                entry.value.state = PubSubState.Unsubscribed
            }
            subscriptionByUri.clear()
        }
        subscriptionsBySubscriptionId.clear()
    }

    internal fun clearAllRegisteredProcedures(e: Throwable?) {
        for ((_, value) in registeredProceduresByUri) {
            if (e == null)
                value.subscriber.onCompleted()
            else
                value.subscriber.onError(e)
            value.state = RegistrationState.Unregistered
        }
        registeredProceduresByUri.clear()
        registeredProceduresById.clear()
    }

    /**
     * Is called if the underlying connection was closed from the remote side.
     * Won't be called if the user issues the close, since the client will then move
     * to the [WaitingForDisconnectState] directly.
     * @param closeReason An optional reason why the connection closed.
     */
    internal fun onConnectionClosed(closeReason: Throwable?) {
        var closeReason = closeReason
        if (closeReason == null)
            closeReason = ApplicationError(ApplicationError.TRANSPORT_CLOSED)
        closeSession(closeReason, null, true)
    }

    internal fun onProtocolError() {
        onSessionError(
                ApplicationError(ApplicationError.PROTCOL_ERROR),
                ApplicationError.PROTCOL_ERROR)
    }

    internal fun onSessionError(error: ApplicationError, closeReason: String) {
        val reconnectAllowed = !stateController.clientConfig().closeClientOnErrors()
        if (!reconnectAllowed) {
            // Record the error that happened during the session
            stateController.setCloseError(error)
        }
        closeSession(error, closeReason, reconnectAllowed)
    }

    internal fun onMessage(msg: WampMessage) {
        if (msg is WelcomeMessage) {
            onProtocolError()
        } else if (msg is ChallengeMessage) {
            onProtocolError()
        } else if (msg is AbortMessage) {
            onProtocolError()
        } else if (msg is GoodbyeMessage) {
            // Reply the goodbye
            // We could also use the reason from the msg, but this would be harder
            // to determinate from a "real" error
            onSessionError(
                    ApplicationError(ApplicationError.GOODBYE_AND_OUT),
                    ApplicationError.GOODBYE_AND_OUT)
        } else if (msg is ResultMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.CallMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val reply = Reply(msg.arguments!!, msg.argumentsKw!!)
            val subject = requestInfo.resultSubject as AsyncSubject<Reply>
            subject.onNext(reply)
            subject.onCompleted()
        } else if (msg is ErrorMessage) {
            if (msg.requestType == WampMessages.CallMessage.ID
                    || msg.requestType == WampMessages.SubscribeMessage.ID
                    || msg.requestType == WampMessages.UnsubscribeMessage.ID
                    || msg.requestType == WampMessages.PublishMessage.ID
                    || msg.requestType == WampMessages.RegisterMessage.ID
                    || msg.requestType == WampMessages.UnregisterMessage.ID) {
                val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the error
                // Check whether the request type we sent equals the
                // request type for the error we receive
                if (requestInfo.requestType != msg.requestType) {
                    onProtocolError()
                    return
                }
                requestMap.remove(msg.requestId)
                val err = ApplicationError(msg.error, msg.arguments, msg.argumentsKw)
                requestInfo.resultSubject.onError(err)
            }
        } else if (msg is SubscribedMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.SubscribeMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val subject = requestInfo.resultSubject as AsyncSubject<Long>
            subject.onNext(msg.subscriptionId)
            subject.onCompleted()
        } else if (msg is UnsubscribedMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.UnsubscribeMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val subject = requestInfo.resultSubject as AsyncSubject<Void>
            subject.onNext(null)
            subject.onCompleted()
        } else if (msg is EventMessage) {
            val entry = subscriptionsBySubscriptionId[msg.subscriptionId]
            if (entry == null || entry.state != PubSubState.Subscribed) return  // Ignore the result
            val evResult = PubSubData(msg.details, msg.arguments, msg.argumentsKw)
            // publish the event
            for (s in entry.subscribers) {
                s.onNext(evResult)
            }
        } else if (msg is PublishedMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.PublishMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val subject = requestInfo.resultSubject as AsyncSubject<Long>
            subject.onNext(msg.publicationId)
            subject.onCompleted()
        } else if (msg is RegisteredMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.RegisterMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val subject = requestInfo.resultSubject as AsyncSubject<Long>
            subject.onNext(msg.registrationId)
            subject.onCompleted()
        } else if (msg is UnregisteredMessage) {
            val requestInfo = requestMap[msg.requestId] ?: return
// Ignore the result
            if (requestInfo.requestType != WampMessages.UnregisterMessage.ID) {
                onProtocolError()
                return
            }
            requestMap.remove(msg.requestId)
            val subject = requestInfo.resultSubject as AsyncSubject<Void>
            subject.onNext(null)
            subject.onCompleted()
        } else if (msg is InvocationMessage) {
            val entry = registeredProceduresById[msg.registrationId]
            if (entry == null || entry.state != RegistrationState.Registered) {
                // Send an error that we are no longer registered
                connectionController.sendMessage(
                        ErrorMessage(InvocationMessage.ID, msg.requestId, null,
                                ApplicationError.NO_SUCH_PROCEDURE, null, null),
                        IWampConnectionPromise.Empty)
            } else {
                // Send the request to the subscriber, which can then send responses
                val request = Request(stateController, this, msg.requestId, msg.arguments, msg.argumentsKw, msg.details)
                entry.subscriber.onNext(request)
            }
        } else {
            // Unknown message
        }
    }

    fun performPublish(topic: String, flags: EnumSet<PublishFlags>?, arguments: ArrayNode,
                       argumentsKw: ObjectNode, resultSubject: AsyncSubject<Long>) {
        val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
        lastRequestId = requestId

        val options = stateController.clientConfig().objectMapper().createObjectNode()
        if (flags != null && flags.contains(PublishFlags.DontExcludeMe)) {
            options.put("exclude_me", false)
        }

        if (flags != null && flags.contains(PublishFlags.RequireAcknowledge)) {
            // An acknowledge from the router in the form of a PUBLISHED or ERROR message
            // is expected. The request is stored in the requestMap and the resultSubject will be
            // completed once a response was received.
            options.put("acknowledge", true)
            requestMap.put(requestId, RequestMapEntry(PublishMessage.ID, resultSubject))
        } else {
            // No acknowledge will be sent from the router.
            // Treat the publish as a success
            resultSubject.onNext(0L)
            resultSubject.onCompleted()
        }

        val msg = WampMessages.PublishMessage(requestId, options, topic, arguments, argumentsKw)

        connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
    }

    fun performCall(procedure: String,
                    flags: EnumSet<CallFlags>?,
                    arguments: ArrayNode,
                    argumentsKw: ObjectNode,
                    resultSubject: AsyncSubject<Reply>) {
        val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
        lastRequestId = requestId

        val options = stateController.clientConfig().objectMapper().createObjectNode()

        val discloseMe = flags != null && flags.contains(CallFlags.DiscloseMe)
        if (discloseMe) {
            options.put("disclose_me", discloseMe)
        }

        val callMsg = CallMessage(requestId, options, procedure,
                arguments, argumentsKw)

        requestMap.put(requestId, RequestMapEntry(CallMessage.ID, resultSubject))
        connectionController.sendMessage(callMsg, IWampConnectionPromise.Empty)
    }

    fun performRegisterProcedure(topic: String, flags: EnumSet<RegisterFlags>?,
                                 subscriber: Subscriber<in Request>) {
        // Check if we have already registered a function with the same name
        val entry = registeredProceduresByUri[topic]
        if (entry != null) {
            subscriber.onError(
                    ApplicationError(ApplicationError.PROCEDURE_ALREADY_EXISTS))
            return
        }

        // Insert a new entry in the subscription map
        val newEntry = RegisteredProceduresMapEntry(subscriber, RegistrationState.Registering)
        registeredProceduresByUri.put(topic, newEntry)

        // Make the subscribe call
        val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
        lastRequestId = requestId

        val options = stateController.clientConfig().objectMapper().createObjectNode()
        if (flags != null && flags.contains(RegisterFlags.DiscloseCaller)) {
            options.put("disclose_caller", true)
        }

        val msg = RegisterMessage(requestId, options, topic)

        val registerFuture = AsyncSubject.create<Long>()
        registerFuture
                .observeOn(stateController.rxScheduler())
                .subscribe(Action1 { t1 ->
                    // Check if we were unsubscribed (through transport close)
                    if (newEntry.state != RegistrationState.Registering) return@Action1
                    // Registration at the broker was successful
                    newEntry.state = RegistrationState.Registered
                    newEntry.registrationId = t1!!
                    registeredProceduresById.put(t1, newEntry)
                    // Add the cancellation functionality to the subscriber
                    attachCancelRegistrationAction(subscriber, newEntry, topic)
                }, Action1 { t1 ->
                    // Error on registering
                    if (newEntry.state != RegistrationState.Registering) return@Action1
                    // Remark: Actually noone can't unregister until this Future completes because
                    // the unregister functionality is only added in the success case
                    // However a transport close event could set us to Unregistered early
                    newEntry.state = RegistrationState.Unregistered

                    var isClosed = false
                    if (t1 is ApplicationError && t1.uri() == ApplicationError.TRANSPORT_CLOSED)
                        isClosed = true

                    if (isClosed)
                        subscriber.onCompleted()
                    else
                        subscriber.onError(t1)

                    registeredProceduresByUri.remove(topic)
                })

        requestMap.put(requestId,
                RequestMapEntry(RegisterMessage.ID, registerFuture))
        connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
    }

    /**
     * Add an action that is added to the subscriber which is executed
     * if unsubscribe is called on a registered procedure.<br></br>
     * This action will lead to unregistering a provided function at the dealer.
     */
    private fun attachCancelRegistrationAction(subscriber: Subscriber<in Request>,
                                               mapEntry: RegisteredProceduresMapEntry,
                                               topic: String) {
        subscriber.add(Subscriptions.create {
            stateController.tryScheduleAction {
                if (mapEntry.state != RegistrationState.Registered) return@tryScheduleAction

                mapEntry.state = RegistrationState.Unregistering
                registeredProceduresByUri.remove(topic)
                registeredProceduresById.remove(mapEntry.registrationId)

                // Make the unregister call
                val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
                lastRequestId = requestId
                val msg = UnregisterMessage(requestId, mapEntry.registrationId)

                val unregisterFuture = AsyncSubject.create<Void>()
                unregisterFuture
                        .observeOn(stateController.rxScheduler())
                        .subscribe({
                            // Unregistration at the broker was successful
                            mapEntry.state = RegistrationState.Unregistered
                        }) {
                            // Error on unregister
                        }

                requestMap.put(requestId, RequestMapEntry(
                        UnregisterMessage.ID, unregisterFuture))
                connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
            }
        })
    }

    fun performSubscription(topic: String,
                            flags: SubscriptionFlags, subscriber: Subscriber<in PubSubData>) {
        // Check if we are already subscribed at the dealer
        val entry = subscriptionsByFlags.get(flags)!![topic]
        if (entry != null) { // We are already subscribed at the dealer
            entry.subscribers.add(subscriber)
            if (entry.state == PubSubState.Subscribed) {
                // Add the cancellation functionality only if we are
                // already subscribed. If not then this will be added
                // once subscription is completed
                attachPubSubCancellationAction(subscriber, entry, topic)
            }
        } else { // need to subscribe
            // Insert a new entry in the subscription map
            val newEntry = SubscriptionMapEntry(flags, PubSubState.Subscribing)
            newEntry.subscribers.add(subscriber)
            subscriptionsByFlags.get(flags)!!.put(topic, newEntry)

            // Make the subscribe call
            val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
            lastRequestId = requestId

            var options: ObjectNode? = null
            if (flags != SubscriptionFlags.Exact) {
                options = stateController.clientConfig().objectMapper().createObjectNode()
                options!!.put("match", flags.name.toLowerCase())
            }
            val msg = SubscribeMessage(requestId, options, topic)

            val subscribeFuture = AsyncSubject.create<Long>()
            subscribeFuture
                    .observeOn(stateController.rxScheduler())
                    .subscribe(Action1 { t1 ->
                        // Check if we were unsubscribed (through transport close)
                        if (newEntry.state != PubSubState.Subscribing) return@Action1
                        // Subscription at the broker was successful
                        newEntry.state = PubSubState.Subscribed
                        newEntry.subscriptionId = t1!!
                        subscriptionsBySubscriptionId.put(t1, newEntry)
                        // Add the cancellation functionality to all subscribers
                        // If one is already unsubscribed this will immediately call
                        // the cancellation function for this subscriber
                        for (s in newEntry.subscribers) {
                            attachPubSubCancellationAction(s, newEntry, topic)
                        }
                    }, Action1 { t1 ->
                        // Error on subscription
                        if (newEntry.state != PubSubState.Subscribing) return@Action1
                        // Remark: Actually noone can't unsubscribe until this Future completes because
                        // the unsubscription functionality is only added in the success case
                        // However a transport close event could set us to Unsubscribed early
                        newEntry.state = PubSubState.Unsubscribed

                        var isClosed = false
                        if (t1 is ApplicationError && t1.uri() == ApplicationError.TRANSPORT_CLOSED)
                            isClosed = true

                        for (s in newEntry.subscribers) {
                            if (isClosed)
                                s.onCompleted()
                            else
                                s.onError(t1)
                        }

                        newEntry.subscribers.clear()
                        subscriptionsByFlags.get(flags)!!.remove(topic)
                    })

            requestMap.put(requestId,
                    RequestMapEntry(SubscribeMessage.ID,
                            subscribeFuture))
            connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
        }
    }

    /**
     * Add an action that is added to the subscriber which is executed
     * if unsubscribe is called. This action will lead to the unsubscription at the
     * broker once the topic subscription at the broker is no longer used by anyone.
     */
    private fun attachPubSubCancellationAction(subscriber: Subscriber<in PubSubData>,
                                               mapEntry: SubscriptionMapEntry,
                                               topic: String) {
        subscriber.add(Subscriptions.create {
            stateController.tryScheduleAction {
                mapEntry.subscribers.remove(subscriber)
                if (mapEntry.state == PubSubState.Subscribed && mapEntry.subscribers.size == 0) {
                    // We removed the last subscriber and can therefore unsubscribe from the dealer
                    mapEntry.state = PubSubState.Unsubscribing
                    subscriptionsByFlags.get(mapEntry.flags)!!.remove(topic)
                    subscriptionsBySubscriptionId.remove(mapEntry.subscriptionId)

                    // Make the unsubscribe call
                    val requestId = IdGenerator.newLinearId(lastRequestId, requestMap)
                    lastRequestId = requestId
                    val msg = UnsubscribeMessage(requestId, mapEntry.subscriptionId)

                    val unsubscribeFuture = AsyncSubject.create<Void>()
                    unsubscribeFuture
                            .observeOn(stateController.rxScheduler())
                            .subscribe({
                                // Unsubscription at the broker was successful
                                mapEntry.state = PubSubState.Unsubscribed
                            }) {
                                // Error on unsubscription
                            }

                    requestMap.put(requestId, RequestMapEntry(
                            UnsubscribeMessage.ID, unsubscribeFuture))
                    connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
                }
            }
        })
    }
}
