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
import com.github.sshaddicts.jawampa.connection.*
import com.github.sshaddicts.jawampa.connection.*

/** The session is trying to connect to the router  */
class ConnectingState(private val stateController: StateController,
                      /** How often connects should be attempted  */
                      private var nrConnectAttempts: Int) : ClientState, IPendingWampConnectionListener {
    /** The currently active connection  */
    private lateinit var connectionController: IConnectionController
    /** The current connection attempt  */
    private lateinit var connectingCon: IPendingWampConnection
    /** Whether the connection attempt is cancelled  */
    private var isCancelled = false

    override fun onEnter(lastState: ClientState) {
        if (lastState is InitialState) {
            stateController.setExternalState(WampClient.ConnectingState())
        }

        // Check for valid number of connects
        assert(nrConnectAttempts != 0)
        // Decrease remaining number of reconnects if it's not infinite
        if (nrConnectAttempts > 0) nrConnectAttempts--

        // Starts an connection attempt to the router
        connectionController = QueueingConnectionController(stateController.scheduler(), ClientConnectionListener(stateController))

        try {
            connectingCon = stateController.clientConfig().connector().connect(stateController.scheduler(), this, connectionController)
        } catch (e: Exception) {
            // Catch exceptions that can happen during creating the channel
            // These are normally signs that something is wrong with our configuration
            // Therefore we don't trigger retries
            stateController.setCloseError(e)
            stateController.setExternalState(WampClient.DisconnectedState(e))
            val newState = DisconnectedState(stateController, e)
            // This is a reentrant call to setState. However it works as onEnter is the last call in setState
            stateController.setState(newState)
        }

    }

    override fun onLeave(newState: ClientState) {

    }

    override fun connectSucceeded(connection: IWampConnection) {
        val wasScheduled = stateController.tryScheduleAction {
            if (!isCancelled) {
                // Our new channel is connected
                connectionController.setConnection(connection)
                val newState = HandshakingState(stateController, connectionController, nrConnectAttempts)
                stateController.setState(newState)
            } else {
                // We we're connected but aren't interested in the channel anymore
                // The client should close
                // Therefore we close the new channel
                stateController.setExternalState(WampClient.DisconnectedState(null))
                val newState = WaitingForDisconnectState(stateController, nrConnectAttempts)
                connection.close(false, newState.closePromise())
                stateController.setState(newState)
            }
        }

        if (!wasScheduled) {
            // If the client was closed before the connection
            // succeeds, close the connection
            connection.close(false, IWampConnectionPromise.Empty)
        }
    }

    override fun connectFailed(cause: Throwable) {
        stateController.tryScheduleAction {
            if (!isCancelled) {
                // Try reconnect if possible, otherwise announce close
                if (nrConnectAttempts != 0) { // Reconnect is allowed
                    val nextState = WaitingForReconnectState(stateController, nrConnectAttempts)
                    stateController.setState(nextState)
                } else {
                    stateController.setExternalState(WampClient.DisconnectedState(cause))
                    val nextState = DisconnectedState(stateController, cause)
                    stateController.setState(nextState)
                }
            } else {
                // Connection cancel attempt was successfully cancelled.
                // This is the final state
                stateController.setExternalState(WampClient.DisconnectedState(null))
                val nextState = DisconnectedState(stateController, null)
                stateController.setState(nextState)
            }
        }
    }

    override fun initClose() {
        if (isCancelled) return
        isCancelled = true
        connectingCon.cancelConnect()
    }
}