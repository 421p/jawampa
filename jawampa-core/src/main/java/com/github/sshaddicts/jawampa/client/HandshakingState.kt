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

import com.github.sshaddicts.jawampa.ApplicationError
import com.github.sshaddicts.jawampa.WampClient
import com.github.sshaddicts.jawampa.WampMessages
import com.github.sshaddicts.jawampa.WampMessages.*
import com.github.sshaddicts.jawampa.WampRoles
import com.github.sshaddicts.jawampa.connection.IConnectionController
import com.github.sshaddicts.jawampa.connection.IWampConnectionPromise
import java.util.*

/**
 * The state where the WAMP handshake (HELLO, WELCOME, ...) is exchanged.
 */
class HandshakingState(private val stateController: StateController,
                       /** The currently active connection  */
                       val connectionController: IConnectionController, private val nrReconnectAttempts: Int) : ClientState {

    private var challengeMsgAllowed = true

    internal var disconnectReason: Throwable? = null

    override fun onEnter(lastState: ClientState) {
        sendHelloMessage()
    }

    override fun onLeave(newState: ClientState) {

    }

    override fun initClose() {
        closeIncompleteSession(null, ApplicationError.SYSTEM_SHUTDOWN, false)
    }

    internal fun closeIncompleteSession(disconnectReason: Throwable?, optAbortReason: String?, reconnectAllowed: Boolean) {
        // Send abort to the remote
        if (optAbortReason != null) {
            val msg = AbortMessage(null, optAbortReason)
            connectionController.sendMessage(msg, IWampConnectionPromise.Empty)
        }

        val nrReconnects = if (reconnectAllowed) nrReconnectAttempts else 0
        if (nrReconnects == 0) {
            stateController.setExternalState(WampClient.DisconnectedState(disconnectReason))
        }
        val newState = WaitingForDisconnectState(stateController, nrReconnects)
        connectionController.close(true, newState.closePromise())
        stateController.setState(newState)
    }

    internal fun handleProtocolError() {
        handleSessionError(
                ApplicationError(ApplicationError.PROTCOL_ERROR),
                ApplicationError.PROTCOL_ERROR)
    }

    internal fun handleSessionError(error: ApplicationError, closeReason: String?) {
        val reconnectAllowed = !stateController.clientConfig().closeClientOnErrors()
        if (!reconnectAllowed) {
            // Record the error that happened during the session
            stateController.setCloseError(error)
        }
        closeIncompleteSession(error, closeReason, reconnectAllowed)
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
        closeIncompleteSession(closeReason, null, true)
    }

    /**
     * Is called after the low-level connection between the client and the server was established
     */
    internal fun sendHelloMessage() {
        // System.out.println("Session websocket connection established");
        // Connection to the remote host was established
        // However the WAMP session is not established until the handshake was finished

        connectionController
                .sendMessage(WampMessages.HelloMessage(stateController.clientConfig().realm(), stateController.clientConfig().helloDetails()), IWampConnectionPromise.Empty)
    }

    internal fun onMessage(msg: WampMessage) {
        // We were not yet welcomed
        if (msg is WelcomeMessage) {
            // Receive a welcome. Now the session is established!
            val welcomeDetails = msg.details
            val sessionId = msg.sessionId

            // Extract the roles of the remote side
            val roleNode = welcomeDetails!!.get("roles")
            if (roleNode == null || !roleNode.isObject) {
                handleProtocolError()
                return
            }

            val routerRoles = EnumSet.noneOf<WampRoles>(WampRoles::class.java)
            val roleKeys = roleNode.fieldNames()
            while (roleKeys.hasNext()) {
                val role = WampRoles.fromString(roleKeys.next())
                if (role != null) routerRoles.add(role)
            }

            val newState = SessionEstablishedState(
                    stateController, connectionController, sessionId, welcomeDetails, routerRoles)
            stateController.setState(newState)
        } else if (msg is ChallengeMessage) {
            if (!challengeMsgAllowed) {
                // Allow Challenge message only a single time
                handleProtocolError()
                return
            }
            challengeMsgAllowed = false

            val authMethodString = msg.authMethod
            val authMethods = stateController.clientConfig().authMethods()

            for (authMethod in authMethods) {
                if (authMethod.authMethod == authMethodString) {
                    val reply = authMethod.handleChallenge(msg, stateController.clientConfig().objectMapper())
                    if (reply == null) {
                        handleProtocolError()
                    } else {
                        connectionController.sendMessage(reply, IWampConnectionPromise.Empty)
                    }
                    return
                }
            }
            handleProtocolError()
        } else if (msg is AbortMessage) {
            // The remote doesn't want us to connect :(
            handleSessionError(ApplicationError(msg.reason), null)
        }
    }
}