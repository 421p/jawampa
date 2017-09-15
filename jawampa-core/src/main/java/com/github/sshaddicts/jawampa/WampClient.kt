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

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import rx.Observable
import rx.Observable.OnSubscribe
import rx.Observer
import rx.exceptions.OnErrorThrowable
import rx.functions.Func1
import rx.subjects.AsyncSubject
import com.github.sshaddicts.jawampa.client.ClientConfiguration
import com.github.sshaddicts.jawampa.client.SessionEstablishedState
import com.github.sshaddicts.jawampa.client.StateController
import com.github.sshaddicts.jawampa.internal.ArgArrayBuilder
import com.github.sshaddicts.jawampa.internal.Promise
import com.github.sshaddicts.jawampa.internal.UriValidator
import java.net.URI
import java.util.*
import java.util.concurrent.Future

/**
 * Provides the client-side functionality for WAMP.<br></br>
 * The [WampClient] allows to make remote procedure calls, subscribe to
 * and publish events and to register functions for RPC.<br></br>
 * It has to be constructed through a [WampClientBuilder] and can not
 * directly be instantiated.
 */
class WampClient internal constructor(internal val clientConfig: ClientConfiguration) {

    /** Base type for all possible client states  */
    interface State

    /** The session is not connected  */
    class DisconnectedState(private val disconnectReason: Throwable?) : State {

        /**
         * Returns an optional reason that describes why the client got
         * disconnected from the server. This can be null if the client
         * requested the disconnect or if the client was never connected
         * to a server.
         */
        fun disconnectReason(): Throwable {
            return disconnectReason!!
        }

        override fun toString(): String {
            return "Disconnected"
        }
    }

    /** The session is trying to connect to the router  */
    class ConnectingState : State {
        override fun toString(): String {
            return "Connecting"
        }
    }

    /**
     * The client is connected to the router and the session was established
     */
    class ConnectedState(private val sessionId: Long, private val welcomeDetails: ObjectNode, private val routerRoles: EnumSet<WampRoles>) : State {

        /** Returns the sessionId that was assigned to the client by the router  */
        fun sessionId(): Long {
            return sessionId
        }

        /**
         * Returns the details of the welcome message that was sent from the router
         * to the client
         */
        fun welcomeDetails(): ObjectNode {
            return welcomeDetails.deepCopy()
        }

        /**
         * Returns the roles that the router implements
         */
        fun routerRoles(): Set<WampRoles> {
            return EnumSet.copyOf(routerRoles)
        }

        override fun toString(): String {
            return "Connected"
        }
    }

    internal val stateController: StateController = StateController(clientConfig)

    /** Returns the URI of the router to which this client is connected  */
    fun routerUri(): URI {
        return clientConfig.routerUri()
    }

    /** Returns the name of the realm on the router  */
    fun realm(): String {
        return clientConfig.realm()
    }

    init {
        // Create a new stateController
    }

    /**
     * Opens the session<br></br>
     * This should be called after a subscription on [.statusChanged]
     * was installed.<br></br>
     * If the session was already opened this has no effect besides
     * resetting the reconnect counter.<br></br>
     * If the session was already closed through a call to [.close]
     * no new connect attempt will be performed.
     */
    fun open() {
        stateController.open()
    }

    /**
     * Closes the session.<br></br>
     * It will not be possible to open the session again with [.open] for safety
     * reasons. If a new session is required a new [WampClient] should be built
     * through the used [WampClientBuilder].
     */
    fun close(): Observable<Void> {
        stateController.initClose()
        return terminationObservable
    }

    /**
     * An Observable that allows to monitor the connection status of the Session.
     */
    fun statusChanged(): Observable<State> {
        return stateController.statusObservable()
    }

    /**
     * Publishes an event under the given topic.
     * @param topic The topic that should be used for publishing the event
     * @param args A list of all positional arguments of the event to publish.
     * These will be get serialized according to the Jackson library serializing
     * behavior.
     * @return An observable that provides a notification whether the event
     * publication was successful. This contains either a single value (the
     * publication ID) and will then be completed or will be completed with
     * an error if the event could not be published.
     */
    fun publish(topic: String, args: List<Any>): Observable<Long> {
        return publish(topic, ArgArrayBuilder.buildArgumentsArray(clientConfig.objectMapper(), *args.toTypedArray()), null)
    }

    /**
     * Publishes an event under the given topic.
     * @param topic The topic that should be used for publishing the event
     * @param event The event to publish
     * @return An observable that provides a notification whether the event
     * publication was successful. This contains either a single value (the
     * publication ID) and will then be completed or will be completed with
     * an error if the event could not be published.
     */
    fun publish(topic: String, event: PubSubData?): Observable<Long> {
        return if (event != null) {
            publish(topic, event.arguments, event.keywordArguments)
        } else
            publish(topic, null, null)
    }

    /**
     * Publishes an event under the given topic.
     * @param topic The topic that should be used for publishing the event
     * @param arguments The positional arguments for the published event
     * @param argumentsKw The keyword arguments for the published event.
     * These will only be taken into consideration if arguments is not null.
     * @return An observable that provides a notification whether the event
     * publication was successful. This contains either a single value (the
     * publication ID) and will then be completed or will be completed with
     * an error if the event could not be published.
     */
    fun publish(topic: String, arguments: ArrayNode?, argumentsKw: ObjectNode?): Observable<Long> {
        return publish(topic, null, arguments, argumentsKw)
    }

    /**
     * Publishes an event under the given topic.
     * @param topic The topic that should be used for publishing the event
     * @param flags Additional publish flags if any. This can be null.
     * @param arguments The positional arguments for the published event
     * @param argumentsKw The keyword arguments for the published event.
     * These will only be taken into consideration if arguments is not null.
     * @return An observable that provides a notification whether the event
     * publication was successful. This contains either a single value (the
     * publication ID) and will then be completed or will be completed with
     * an error if the event could not be published.
     */
    fun publish(topic: String, flags: EnumSet<PublishFlags>?, arguments: ArrayNode?,
                argumentsKw: ObjectNode?): Observable<Long> {
        val resultSubject = AsyncSubject.create<Long>()

        try {
            UriValidator.validate(topic, clientConfig.useStrictUriValidation())
        } catch (e: WampError) {
            resultSubject.onError(e)
            return resultSubject
        }

        val wasScheduled = stateController.tryScheduleAction {
            if (stateController.currentState() !is SessionEstablishedState) {
                resultSubject.onError(ApplicationError(ApplicationError.NOT_CONNECTED))
                return@tryScheduleAction
            }
            // Forward publish into the session
            val curState = stateController.currentState() as SessionEstablishedState
            curState.performPublish(topic, flags, arguments!!, argumentsKw!!, resultSubject)
        }

        if (!wasScheduled) {
            resultSubject.onError(
                    ApplicationError(ApplicationError.CLIENT_CLOSED))
        }
        return resultSubject
    }

    /**
     * Registers a procedure at the router which will afterwards be available
     * for remote procedure calls from other clients.<br></br>
     * The actual registration will only happen after the user subscribes on
     * the returned Observable. This guarantees that no RPC requests get lost.
     * Incoming RPC requests will be pushed to the Subscriber via it's
     * onNext method. The Subscriber can send responses through the methods on
     * the [Request].<br></br>
     * If the client no longer wants to provide the method it can call
     * unsubscribe() on the Subscription to unregister the procedure.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The name of the procedure which this client wants to
     * provide.<br></br>
     * Must be valid WAMP URI.
     * @return An observable that can be used to provide a procedure.
     */
    fun registerProcedure(topic: String): Observable<Request> {
        return this.registerProcedure(topic, EnumSet.noneOf(RegisterFlags::class.java))
    }

    /**
     * Registers a procedure at the router which will afterwards be available
     * for remote procedure calls from other clients.<br></br>
     * The actual registration will only happen after the user subscribes on
     * the returned Observable. This guarantees that no RPC requests get lost.
     * Incoming RPC requests will be pushed to the Subscriber via it's
     * onNext method. The Subscriber can send responses through the methods on
     * the [Request].<br></br>
     * If the client no longer wants to provide the method it can call
     * unsubscribe() on the Subscription to unregister the procedure.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The name of the procedure which this client wants to
     * provide.<br></br>
     * Must be valid WAMP URI.
     * @param flags procedure flags
     * @return An observable that can be used to provide a procedure.
     */
    fun registerProcedure(topic: String, vararg flags: RegisterFlags): Observable<Request> {
        return this.registerProcedure(topic, EnumSet.copyOf(Arrays.asList(*flags)))
    }

    /**
     * Registers a procedure at the router which will afterwards be available
     * for remote procedure calls from other clients.<br></br>
     * The actual registration will only happen after the user subscribes on
     * the returned Observable. This guarantees that no RPC requests get lost.
     * Incoming RPC requests will be pushed to the Subscriber via it's
     * onNext method. The Subscriber can send responses through the methods on
     * the [Request].<br></br>
     * If the client no longer wants to provide the method it can call
     * unsubscribe() on the Subscription to unregister the procedure.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The name of the procedure which this client wants to
     * provide.<br></br>
     * Must be valid WAMP URI.
     * @param flags procedure flags
     * @return An observable that can be used to provide a procedure.
     */
    fun registerProcedure(topic: String, flags: EnumSet<RegisterFlags>): Observable<Request> {
        return Observable.create(OnSubscribe { subscriber ->
            try {
                UriValidator.validate(topic, clientConfig.useStrictUriValidation())
            } catch (e: WampError) {
                subscriber.onError(e)
                return@OnSubscribe
            }

            val wasScheduled = stateController.tryScheduleAction {
                // If the Subscriber unsubscribed in the meantime we return early
                if (subscriber.isUnsubscribed) return@tryScheduleAction
                // Set subscription to completed if we are not connected
                if (stateController.currentState() !is SessionEstablishedState) {
                    subscriber.onCompleted()
                    return@tryScheduleAction
                }
                // Forward publish into the session
                val curState = stateController.currentState() as SessionEstablishedState
                curState.performRegisterProcedure(topic, flags, subscriber)
            }

            if (!wasScheduled) {
                subscriber.onError(
                        ApplicationError(ApplicationError.CLIENT_CLOSED))
            }
        })
    }

    /**
     * Returns an observable that allows to subscribe on the given topic.<br></br>
     * The actual subscription will only be made after subscribe() was called
     * on it.<br></br>
     * This version of makeSubscription will automatically transform the
     * received events data into the type eventClass and will therefore return
     * a mapped Observable. It will only look at and transform the first
     * argument of the received events arguments, therefore it can only be used
     * for events that carry either a single or no argument.<br></br>
     * Received publications will be pushed to the Subscriber via it's
     * onNext method.<br></br>
     * The client can unsubscribe from the topic by calling unsubscribe() on
     * it's Subscription.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The topic to subscribe on.<br></br>
     * Must be valid WAMP URI.
     * @param eventClass The class type into which the received event argument
     * should be transformed. E.g. use String.class to let the client try to
     * transform the first argument into a String and let the return value of
     * of the call be Observable&lt;String&gt;.
     * @return An observable that can be used to subscribe on the topic.
     */
    fun <T> makeSubscription(topic: String, eventClass: Class<T>): Observable<T> {
        return makeSubscription(topic, SubscriptionFlags.Exact, eventClass)
    }

    /**
     * Returns an observable that allows to subscribe on the given topic.<br></br>
     * The actual subscription will only be made after subscribe() was called
     * on it.<br></br>
     * This version of makeSubscription will automatically transform the
     * received events data into the type eventClass and will therefore return
     * a mapped Observable. It will only look at and transform the first
     * argument of the received events arguments, therefore it can only be used
     * for events that carry either a single or no argument.<br></br>
     * Received publications will be pushed to the Subscriber via it's
     * onNext method.<br></br>
     * The client can unsubscribe from the topic by calling unsubscribe() on
     * it's Subscription.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The topic to subscribe on.<br></br>
     * Must be valid WAMP URI.
     * @param flags Flags to indicate type of subscription. This cannot be null.
     * @param eventClass The class type into which the received event argument
     * should be transformed. E.g. use String.class to let the client try to
     * transform the first argument into a String and let the return value of
     * of the call be Observable&lt;String&gt;.
     * @return An observable that can be used to subscribe on the topic.
     */
    fun <T> makeSubscription(topic: String, flags: SubscriptionFlags, eventClass: Class<T>?): Observable<T> {
        return makeSubscription(topic, flags).map(Func1 { ev ->
            if (eventClass == null || eventClass == Void::class.java) {
                // We don't need a value
                return@Func1 null
            }

            if (ev.arguments == null || ev.arguments.size() < 1)
                throw OnErrorThrowable.from(ApplicationError(ApplicationError.MISSING_VALUE))

            val eventNode = ev.arguments.get(0)
            if (eventNode.isNull) return@Func1 null

            val eventValue: T
            try {
                eventValue = clientConfig.objectMapper().convertValue(eventNode, eventClass)
            } catch (e: IllegalArgumentException) {
                throw OnErrorThrowable.from(ApplicationError(ApplicationError.INVALID_VALUE_TYPE))
            }

            eventValue
        })
    }

    /**
     * Returns an observable that allows to subscribe on the given topic.<br></br>
     * The actual subscription will only be made after subscribe() was called
     * on it.<br></br>
     * makeSubscriptionWithDetails will automatically transform the
     * received events data into the type eventClass and will therefore return
     * a mapped Observable of type EventDetails. It will only look at and transform the first
     * argument of the received events arguments, therefore it can only be used
     * for events that carry either a single or no argument.<br></br>
     * Received publications will be pushed to the Subscriber via it's
     * onNext method.<br></br>
     * The client can unsubscribe from the topic by calling unsubscribe() on
     * it's Subscription.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The topic to subscribe on.<br></br>
     * Must be valid WAMP URI.
     * @param flags Flags to indicate type of subscription. This cannot be null.
     * @param eventClass The class type into which the received event argument
     * should be transformed. E.g. use String.class to let the client try to
     * transform the first argument into a String and let the return value of
     * of the call be Observable&lt;EventDetails&lt;String&gt;&gt;.
     * @return An observable of type EventDetails that can be used to subscribe on the topic.
     * EventDetails contains topic and message. EventDetails.topic can be useful in getting
     * the complete topic name during wild card or prefix subscriptions
     */
    fun <T> makeSubscriptionWithDetails(topic: String, flags: SubscriptionFlags, eventClass: Class<T>?): Observable<EventDetails<T>> {
        return makeSubscription(topic, flags).map(Func1 { ev ->
            if (eventClass == null || eventClass == Void::class.java) {
                // We don't need a value
                return@Func1 null
            }

            //get the complete topic name
            //which may not be the same as method parameter 'topic' during wildcard or prefix subscriptions
            val actualTopic: String? = ev.details?.get("topic")?.asText()

            if (ev.arguments!!.size() < 1)
                throw OnErrorThrowable.from(ApplicationError(ApplicationError.MISSING_VALUE))

            val eventNode = ev.arguments.get(0)
            if (eventNode.isNull) return@Func1 null

            val eventValue: T
            try {
                eventValue = clientConfig.objectMapper().convertValue(eventNode, eventClass)
            } catch (e: IllegalArgumentException) {
                throw OnErrorThrowable.from(ApplicationError(ApplicationError.INVALID_VALUE_TYPE))
            }

            EventDetails(eventValue, actualTopic!!)
        })
    }

    /**
     * Returns an observable that allows to subscribe on the given topic.<br></br>
     * The actual subscription will only be made after subscribe() was called
     * on it.<br></br>
     * Received publications will be pushed to the Subscriber via it's
     * onNext method.<br></br>
     * The client can unsubscribe from the topic by calling unsubscribe() on
     * it's Subscription.<br></br>
     * If the connection closes onCompleted will be called.<br></br>
     * In case of errors during subscription onError will be called.
     * @param topic The topic to subscribe on.<br></br>
     * Must be valid WAMP URI.
     * @param flags Flags to indicate type of subscription. This cannot be null.
     * @return An observable that can be used to subscribe on the topic.
     */
    @JvmOverloads
    fun makeSubscription(topic: String, flags: SubscriptionFlags = SubscriptionFlags.Exact): Observable<PubSubData> {
        return Observable.create(OnSubscribe { subscriber ->
            try {
                if (flags == SubscriptionFlags.Exact) {
                    UriValidator.validate(topic, clientConfig.useStrictUriValidation())
                } else if (flags == SubscriptionFlags.Prefix) {
                    UriValidator.validatePrefix(topic, clientConfig.useStrictUriValidation())
                } else if (flags == SubscriptionFlags.Wildcard) {
                    UriValidator.validateWildcard(topic, clientConfig.useStrictUriValidation())
                }
            } catch (e: WampError) {
                subscriber.onError(e)
                return@OnSubscribe
            }

            val wasScheduled = stateController.tryScheduleAction {
                // If the Subscriber unsubscribed in the meantime we return early
                if (subscriber.isUnsubscribed) return@tryScheduleAction
                // Set subscription to completed if we are not connected
                if (stateController.currentState() !is SessionEstablishedState) {
                    subscriber.onCompleted()
                    return@tryScheduleAction
                }
                // Forward performing actual subscription into the session
                val curState = stateController.currentState() as SessionEstablishedState
                curState.performSubscription(topic, flags, subscriber)
            }

            if (!wasScheduled) {
                subscriber.onError(
                        ApplicationError(ApplicationError.CLIENT_CLOSED))
            }
        })
    }

    /**
     * Performs a remote procedure call through the router.<br></br>
     * The function will return immediately, as the actual call will happen
     * asynchronously.
     * @param procedure The name of the procedure to call. Must be a valid WAMP
     * Uri.
     * @param arguments A list of all positional arguments for the procedure call
     * @param argumentsKw All named arguments for the procedure call
     * @return An observable that provides a notification whether the call was
     * was successful and the return value. If the call is successful the
     * returned observable will be completed with a single value (the return value).
     * If the remote procedure call yields an error the observable will be completed
     * with an error.
     */
    fun call(procedure: String,
             arguments: ArrayNode?,
             argumentsKw: ObjectNode?): Observable<Reply> {
        return call(procedure, null, arguments, argumentsKw)
    }


    /**
     * Performs a remote procedure call through the router.<br></br>
     * The function will return immediately, as the actual call will happen
     * asynchronously.
     * @param procedure The name of the procedure to call. Must be a valid WAMP
     * Uri.
     * @param flags Additional call flags if any. This can be null.
     * @param arguments A list of all positional arguments for the procedure call
     * @param argumentsKw All named arguments for the procedure call
     * @return An observable that provides a notification whether the call was
     * was successful and the return value. If the call is successful the
     * returned observable will be completed with a single value (the return value).
     * If the remote procedure call yields an error the observable will be completed
     * with an error.
     */
    fun call(procedure: String,
             flags: EnumSet<CallFlags>?,
             arguments: ArrayNode?,
             argumentsKw: ObjectNode?): Observable<Reply> {
        val resultSubject = AsyncSubject.create<Reply>()

        try {
            UriValidator.validate(procedure, clientConfig.useStrictUriValidation())
        } catch (e: WampError) {
            resultSubject.onError(e)
            return resultSubject
        }

        val wasScheduled = stateController.tryScheduleAction {
            if (stateController.currentState() !is SessionEstablishedState) {
                resultSubject.onError(ApplicationError(ApplicationError.NOT_CONNECTED))
                return@tryScheduleAction
            }

            // Forward performing actual call into the session
            val curState = stateController.currentState() as SessionEstablishedState
            if (argumentsKw != null) {
                curState.performCall(procedure, flags, arguments!!, argumentsKw, resultSubject)
            }
        }

        if (!wasScheduled) {
            resultSubject.onError(
                    ApplicationError(ApplicationError.CLIENT_CLOSED))
        }

        return resultSubject
    }

    /**
     * Performs a remote procedure call through the router.<br></br>
     * The function will return immediately, as the actual call will happen
     * asynchronously.
     * @param procedure The name of the procedure to call. Must be a valid WAMP
     * Uri.
     * @param args The list of positional arguments for the remote procedure call.
     * These will be get serialized according to the Jackson library serializing
     * behavior.
     * @return An observable that provides a notification whether the call was
     * was successful and the return value. If the call is successful the
     * returned observable will be completed with a single value (the return value).
     * If the remote procedure call yields an error the observable will be completed
     * with an error.
     */
    fun call(procedure: String, vararg args: Any): Observable<Reply> {
        // Build the arguments array and serialize the arguments
        return call(procedure, ArgArrayBuilder.buildArgumentsArray(clientConfig.objectMapper(), *args), null)
    }

    /**
     * Performs a remote procedure call through the router.<br></br>
     * The function will return immediately, as the actual call will happen
     * asynchronously.<br></br>
     * This overload of the call function will automatically map the received
     * reply value into the specified Java type by using Jacksons object mapping
     * facilities.<br></br>
     * Only the first value in the array of positional arguments will be taken
     * into account for the transformation. If multiple return values are required
     * another overload of this function has to be used.<br></br>
     * If the expected return type is not [Void] but the return value array
     * contains no value or if the value in the array can not be deserialized into
     * the expected type the returned [Observable] will be completed with
     * an error.
     * @param procedure The name of the procedure to call. Must be a valid WAMP
     * Uri.
     * @param returnValueClass The class of the expected return value. If the function
     * uses no return values Void should be used.
     * @param args The list of positional arguments for the remote procedure call.
     * These will be get serialized according to the Jackson library serializing
     * behavior.
     * @return An observable that provides a notification whether the call was
     * was successful and the return value. If the call is successful the
     * returned observable will be completed with a single value (the return value).
     * If the remote procedure call yields an error the observable will be completed
     * with an error.
     */
    fun <T> call(procedure: String,
                 returnValueClass: Class<T>, vararg args: Any): Observable<T> {
        return call<T>(procedure, null, returnValueClass, *args)
    }

    /**
     * Performs a remote procedure call through the router.<br></br>
     * The function will return immediately, as the actual call will happen
     * asynchronously.<br></br>
     * This overload of the call function will automatically map the received
     * reply value into the specified Java type by using Jacksons object mapping
     * facilities.<br></br>
     * Only the first value in the array of positional arguments will be taken
     * into account for the transformation. If multiple return values are required
     * another overload of this function has to be used.<br></br>
     * If the expected return type is not [Void] but the return value array
     * contains no value or if the value in the array can not be deserialized into
     * the expected type the returned [Observable] will be completed with
     * an error.
     * @param procedure The name of the procedure to call. Must be a valid WAMP
     * Uri.
     * @param flags Additional call flags if any. This can be null.
     * @param returnValueClass The class of the expected return value. If the function
     * uses no return values Void should be used.
     * @param args The list of positional arguments for the remote procedure call.
     * These will be get serialized according to the Jackson library serializing
     * behavior.
     * @return An observable that provides a notification whether the call was
     * was successful and the return value. If the call is successful the
     * returned observable will be completed with a single value (the return value).
     * If the remote procedure call yields an error the observable will be completed
     * with an error.
     */
    fun <T> call(procedure: String, flags: EnumSet<CallFlags>?,
                 returnValueClass: Class<T>?, vararg args: Any): Observable<T> {
        return call(procedure, flags, ArgArrayBuilder.buildArgumentsArray(clientConfig.objectMapper(), *args), null)
                .map { reply ->
                    if (returnValueClass == null || returnValueClass == Void::class.java) {
                        // We don't need a return value
                        return@map null
                    }

                    if (reply.arguments.size() < 1)
                        throw OnErrorThrowable.from(ApplicationError(ApplicationError.MISSING_RESULT))

                    val resultNode = reply.arguments.get(0)
                    if (resultNode.isNull) return@map null

                    val result: T
                    try {
                        result = clientConfig.objectMapper().convertValue(resultNode, returnValueClass)
                    } catch (e: IllegalArgumentException) {
                        // The returned exception is an aggregate one. That's not too nice :(
                        throw OnErrorThrowable.from(ApplicationError(ApplicationError.INVALID_VALUE_TYPE))
                    }

                    result
                }
    }

    /**
     * Returns an observable that will be completed with a single value once the client terminates.<br></br>
     * This can be used to asynchronously wait for completion after [close][.close] was called.
     */
    val terminationObservable: Observable<Void>
        get() {
            val termSubject = AsyncSubject.create<Void>()
            stateController.statusObservable().subscribe(object : Observer<State> {
                override fun onCompleted() {
                    termSubject.onNext(null)
                    termSubject.onCompleted()
                }

                override fun onError(e: Throwable) {
                    termSubject.onNext(null)
                    termSubject.onCompleted()
                }

                override fun onNext(t: State) {}
            })
            return termSubject
        }

    /**
     * Returns a future that will be completed once the client terminates.<br></br>
     * This can be used to wait for completion after [close][.close] was called.
     */
    val terminationFuture: Future<Void>
        get() {
            val p = Promise<Void>()
            stateController.statusObservable().subscribe(object : Observer<State> {
                override fun onCompleted() {
                    p.resolve(null)
                }

                override fun onError(e: Throwable) {
                    p.resolve(null)
                }

                override fun onNext(t: State) {}
            })
            return p.future
        }

}
/**
 * Returns an observable that allows to subscribe on the given topic.<br></br>
 * The actual subscription will only be made after subscribe() was called
 * on it.<br></br>
 * Received publications will be pushed to the Subscriber via it's
 * onNext method.<br></br>
 * The client can unsubscribe from the topic by calling unsubscribe() on
 * it's Subscription.<br></br>
 * If the connection closes onCompleted will be called.<br></br>
 * In case of errors during subscription onError will be called.
 * @param topic The topic to subscribe on.<br></br>
 * Must be valid WAMP URI.
 * @return An observable that can be used to subscribe on the topic.
 */
