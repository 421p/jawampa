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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.sshaddicts.jawampa.WampRoles
import com.github.sshaddicts.jawampa.auth.client.ClientSideAuthentication
import com.github.sshaddicts.jawampa.connection.IWampConnector
import com.github.sshaddicts.jawampa.connection.IWampConnectorProvider
import com.github.sshaddicts.jawampa.internal.Version
import java.net.URI
import java.util.*

/**
 * Stores various configuration data for WAMP clients
 */
class ClientConfiguration(
        private val closeClientOnErrors: Boolean,
        private val authId: String?,
        private val authMethods: List<ClientSideAuthentication>?,
        private val routerUri: URI,
        private val realm: String,
        private val useStrictUriValidation: Boolean,
        private val clientRoles: Array<WampRoles?>,
        private val totalNrReconnects: Int,
        private val reconnectInterval: Int,
        /** The provider that should be used to obtain a connector  */
        private val connectorProvider: IWampConnectorProvider,
        /** The connector which is used to create new connections to the remote peer  */
        private val connector: IWampConnector, private val objectMapper: ObjectMapper) {

    private val helloDetails: ObjectNode

    init {

        // Put the requested roles in the Hello message
        helloDetails = this.objectMapper.createObjectNode()
        helloDetails.put("agent", Version.version)

        val rolesNode = helloDetails.putObject("roles")
        for (role in clientRoles) {
            val roleNode = rolesNode.putObject(role.toString())
            if (role == WampRoles.Publisher) {
                val featuresNode = roleNode.putObject("features")
                featuresNode.put("publisher_exclusion", true)
            } else if (role == WampRoles.Subscriber) {
                val featuresNode = roleNode.putObject("features")
                featuresNode.put("pattern_based_subscription", true)
            } else if (role == WampRoles.Caller) {
                val featuresNode = roleNode.putObject("features")
                featuresNode.put("caller_identification", true)
            }
        }

        // Insert authentication data
        if (authId != null) {
            helloDetails.put("authid", authId)
        }
        if (authMethods != null && authMethods.size != 0) {
            val authMethodsNode = helloDetails.putArray("authmethods")
            for (authMethod in authMethods) {
                authMethodsNode.add(authMethod.authMethod)
            }
        }
    }

    fun closeClientOnErrors(): Boolean {
        return closeClientOnErrors
    }

    fun objectMapper(): ObjectMapper {
        return objectMapper
    }

    fun routerUri(): URI {
        return routerUri
    }

    fun realm(): String {
        return realm
    }

    fun useStrictUriValidation(): Boolean {
        return useStrictUriValidation
    }

    fun totalNrReconnects(): Int {
        return totalNrReconnects
    }

    fun reconnectInterval(): Int {
        return reconnectInterval
    }

    /** The connector which is used to create new connections to the remote peer  */
    fun connector(): IWampConnector {
        return connector
    }

    fun clientRoles(): Array<WampRoles?> {
        return clientRoles.clone()
    }

    fun authId(): String? {
        return authId
    }

    fun authMethods(): List<ClientSideAuthentication> {
        return ArrayList(authMethods!!)
    }

    fun connectorProvider(): IWampConnectorProvider {
        return connectorProvider
    }

    internal fun helloDetails(): ObjectNode {
        return helloDetails
    }
}
