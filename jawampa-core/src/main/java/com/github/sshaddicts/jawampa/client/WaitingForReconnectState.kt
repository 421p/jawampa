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

import rx.Subscription
import rx.functions.Action0
import com.github.sshaddicts.jawampa.WampClient
import java.util.concurrent.TimeUnit

class WaitingForReconnectState(private val stateController: StateController, private val nrReconnectAttempts: Int) : ClientState {
    private lateinit var reconnectSubscription: Subscription

    override fun onEnter(lastState: ClientState) {
        reconnectSubscription = stateController.rxScheduler().createWorker().schedule(Action0 {
            if (stateController.currentState() !== this@WaitingForReconnectState) return@Action0
            // Reconnect now
            val newState = ConnectingState(stateController, nrReconnectAttempts)
            stateController.setState(newState)
        }, stateController.clientConfig().reconnectInterval().toLong(), TimeUnit.MILLISECONDS)
    }

    override fun onLeave(newState: ClientState) {

    }

    override fun initClose() {
        reconnectSubscription.unsubscribe()
        // Current external state is Connecting
        // Move to disconnected
        stateController.setExternalState(WampClient.DisconnectedState(null))
        // And switch the internal state also to Disconnected
        val newState = DisconnectedState(stateController, null)
        stateController.setState(newState)
    }
}