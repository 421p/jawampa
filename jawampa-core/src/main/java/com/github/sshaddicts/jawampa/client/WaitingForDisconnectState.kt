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

import com.github.sshaddicts.jawampa.WampClient
import com.github.sshaddicts.jawampa.connection.ICompletionCallback
import com.github.sshaddicts.jawampa.connection.IWampConnectionFuture
import com.github.sshaddicts.jawampa.connection.WampConnectionPromise

/**
 * The client is waiting for a no longer used connection to close
 */
class WaitingForDisconnectState(private val stateController: StateController, private var nrReconnectAttempts: Int) : ClientState, ICompletionCallback<Void> {
    internal var closePromise = WampConnectionPromise(this, null)

    fun nrReconnectAttempts(): Int {
        return nrReconnectAttempts
    }

    override fun onEnter(lastState: ClientState) {

    }

    override fun onLeave(newState: ClientState) {

    }

    override fun initClose() {
        if (nrReconnectAttempts != 0) {
            // Cancelling a reconnect triggers a state transition
            nrReconnectAttempts = 0
            stateController.setExternalState(WampClient.DisconnectedState(null))
        }
    }

    /**
     * Gets the associated promise that should be completed when the
     * connection finally closes.
     */
    fun closePromise(): WampConnectionPromise<Void> {
        return closePromise
    }

    override fun onCompletion(future: IWampConnectionFuture<Void>) {
        // Is called once the disconnect from the previous transport has happened
        if (nrReconnectAttempts == 0) {
            val newState = DisconnectedState(stateController, null)
            stateController.setState(newState)
        } else {
            val newState = WaitingForReconnectState(stateController, nrReconnectAttempts)
            stateController.setState(newState)
        }
    }


}