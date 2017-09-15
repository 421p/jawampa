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

import rx.Observable
import rx.Scheduler
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import com.github.sshaddicts.jawampa.WampClient
import com.github.sshaddicts.jawampa.WampClient.DisconnectedState
import com.github.sshaddicts.jawampa.WampClient.State
import com.github.sshaddicts.jawampa.WampMessages.WampMessage
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService

class StateController(private val clientConfig: ClientConfiguration) {
    private var isCompleted = false

    private var currentState: ClientState? = InitialState(this)

    private val scheduler: ScheduledExecutorService = clientConfig.connectorProvider().createScheduler()
    private val rxScheduler: Scheduler

    /** The current externally visible status  */
    private var extState: State = DisconnectedState(null)
    /** Holds the final value with which [WampClient.status] will be completed  */
    private var closeError: Throwable? = null
    /** Observable that provides the external state  */
    private val statusObservable = BehaviorSubject.create(extState)

    init {
        this.rxScheduler = Schedulers.from(scheduler)
    }

    fun clientConfig(): ClientConfiguration {
        return clientConfig
    }

    fun scheduler(): ScheduledExecutorService {
        return scheduler
    }

    fun rxScheduler(): Scheduler {
        return rxScheduler
    }

    fun statusObservable(): Observable<State> {
        return statusObservable
    }

    fun setExternalState(newState: State) {
        extState = newState
        statusObservable.onNext(extState)
    }

    fun setCloseError(closeError: Throwable) {
        this.closeError = closeError
    }

    /**
     * Tries to schedule a runnable on the provided scheduler.<br></br>
     * Rejected executions will be suppressed.
     *
     * @param action The action to schedule.
     * @return true if the Runnable could be scheduled, false otherwise
     */
    fun tryScheduleAction(action: () -> Unit): Boolean {
        try {
            scheduler.execute(action)
            // Ignore this exception
            // The scheduling will be performed with best effort
            return true
        } catch (e: RejectedExecutionException) {
            return false
        }

    }

    fun currentState(): ClientState? {
        return currentState
    }

    fun setState(newState: ClientState) {
        val lastState = currentState
        lastState?.onLeave(newState)
        currentState = newState
        newState.onEnter(lastState!!)
    }

    /**
     * Is called when the underlying connection received a message from the remote side.
     * @param message The received message
     */
    internal fun onMessage(message: WampMessage) {
        if (currentState is SessionEstablishedState)
            (currentState as SessionEstablishedState).onMessage(message)
        else if (currentState is HandshakingState)
            (currentState as HandshakingState).onMessage(message)
    }

    /**
     * Is called if the underlying connection was closed from the remote side.
     * Won't be called if the user issues the close, since the client will then move
     * to the [WaitingForDisconnectState] directly.
     * @param closeReason An optional reason why the connection closed.
     */
    internal fun onConnectionClosed(closeReason: Throwable) {
        if (currentState is SessionEstablishedState)
            (currentState as SessionEstablishedState).onConnectionClosed(closeReason)
        else if (currentState is HandshakingState)
            (currentState as HandshakingState).onConnectionClosed(closeReason)
    }

    /**
     * Initiates the open process.<br></br>
     * If open was initiated before nothing will happen.
     */
    fun open() {
        scheduler.execute(Runnable {
            if (currentState !is InitialState) return@Runnable
            // Try to connect afterwards
            // This guarantees that the external state will always
            // switch to connecting, even when the attempt immediately
            // fails
            var nrConnects = clientConfig.totalNrReconnects()
            if (nrConnects == 0) nrConnects = 1
            val newState = ConnectingState(this@StateController, nrConnects)
            setState(newState)
        })
    }

    /**
     * Initiates the close process.<br></br>
     * Will be called on [WampClient.close] of the client
     */
    fun initClose() {
        tryScheduleAction {
            if (isCompleted) return@tryScheduleAction // Check if already closed
            isCompleted = true

            // Initialize the close sequence
            // The state will try to move to the final state
            currentState!!.initClose()
        }
    }

    /**
     * Performs the shutdown once the statemachine is in it's terminal state.
     */
    fun performShutdown() {
        if (closeError != null)
            statusObservable.onError(closeError)
        else
            statusObservable.onCompleted()
        scheduler.shutdown()
    }
}
