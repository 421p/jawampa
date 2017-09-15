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
import com.github.sshaddicts.jawampa.auth.client.ClientSideAuthentication
import com.github.sshaddicts.jawampa.client.ClientConfiguration
import com.github.sshaddicts.jawampa.connection.IWampClientConnectionConfig
import com.github.sshaddicts.jawampa.connection.IWampConnectorProvider
import com.github.sshaddicts.jawampa.internal.UriValidator
import java.net.URI
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WampClientBuilder is the builder object which allows to create
 * [WampClient] objects.<br></br>
 * New clients have to be configured with the member methods before
 * a client can be created with the [build()][.build] method.
 */
class WampClientBuilder {

    private var uri: String? = null
    private var realm: String? = null

    private var nrReconnects = 0
    private var reconnectInterval = DEFAULT_RECONNECT_INTERVAL
    private var useStrictUriValidation = false
    private var closeOnErrors = true
    private val roles: EnumSet<WampRoles>
    private val serializations = ArrayList<WampSerialization>()
    private var authId: String? = null
    private val authMethods = ArrayList<ClientSideAuthentication>()

    private var connectionConfiguration: IWampClientConnectionConfig? = null
    private var connectorProvider: IWampConnectorProvider? = null

    private var objectMapper: ObjectMapper? = null

    /**
     * Construct a new WampClientBuilder object.
     */
    init {
        // Add the default roles
        roles = EnumSet.of(
                WampRoles.Caller,
                WampRoles.Callee,
                WampRoles.Publisher,
                WampRoles.Subscriber)

        WampSerialization.addDefaultSerializations(serializations)
    }

    /**
     * Builds a new [WAMP client][WampClient] from the information given in this builder.<br></br>
     * At least the uri of the router and the name of the realm have to be
     * set for a proper operation.
     * @return The created WAMP client
     * @throws WampError if any parameter is not invalid
     */
    @Throws(Exception::class)
    fun build(): WampClient {
        if (uri == null)
            throw ApplicationError(ApplicationError.INVALID_URI)

        val routerUri: URI
        try {
            routerUri = URI(uri!!)
        } catch (t: Throwable) {
            throw ApplicationError(ApplicationError.INVALID_URI)
        }

        if (realm == null)
            throw ApplicationError(ApplicationError.INVALID_REALM)

        try {
            UriValidator.validate(realm!!, useStrictUriValidation)
        } catch (e: Exception) {
            throw ApplicationError(ApplicationError.INVALID_REALM)
        }

        if (roles.size == 0) {
            throw ApplicationError(ApplicationError.INVALID_ROLES)
        }

        // Build the roles array from the roles set
        val rolesArray = arrayOfNulls<WampRoles>(roles.size)
        var i = 0
        for (r in roles) {
            rolesArray[i] = r
            i++
        }

        if (serializations.size == 0) {
            throw ApplicationError(ApplicationError.INVALID_SERIALIZATIONS)
        }

        if (connectorProvider == null)
            throw ApplicationError(ApplicationError.INVALID_CONNECTOR_PROVIDER)

        // Build a connector that can be used by the client afterwards
        // This can throw!
        val connector = connectorProvider!!.createConnector(routerUri, connectionConfiguration!!, serializations)

        // Use default object mapper
        if (objectMapper == null)
            objectMapper = ObjectMapper()

        val clientConfig = ClientConfiguration(
                closeOnErrors, authId, authMethods, routerUri, realm!!,
                useStrictUriValidation, rolesArray, nrReconnects, reconnectInterval,
                connectorProvider!!, connector, objectMapper!!)

        return WampClient(clientConfig)
    }

    /**
     * Sets the address of the router to which the new client shall connect.
     * @param uri The address of the router, e.g. ws://wamp.ws/ws
     * @return The [WampClientBuilder] object
     */
    fun withUri(uri: String): WampClientBuilder {
        this.uri = uri
        return this
    }

    /**
     * Sets the name of the realm on the router which shall be used for the session.
     * @param realm The name of the realm to which shall be connected.
     * @return The [WampClientBuilder] object
     */
    fun withRealm(realm: String): WampClientBuilder {
        this.realm = realm
        return this
    }

    /**
     * Adjusts the roles that this client should have in the session.<br></br>
     * By default a client will have all roles (caller, callee, publisher, subscriber).
     * Use this function to adjust the roles if not all are needed.
     * @param roles The set of roles that the client should fulfill in the session.
     * At least one role is required, otherwise the session can not be established.
     * @return The [WampClientBuilder] object
     */
    fun withRoles(roles: Array<WampRoles>?): WampClientBuilder {
        this.roles.clear()
        if (roles == null) return this // Will throw on build()
        for (role in roles) {
            this.roles.add(role)
        }
        return this
    }

    /**
     * Assigns a connector provider to the WampClient. This provider will be used to
     * establish connections to the server.<br></br>
     * By using a different ConnectorProvider a different transport framework can be used
     * for data exchange between client and server.
     * @param provider The [IWampConnectorProvider] that should be used
     * @return The [WampClientBuilder] object
     */
    fun withConnectorProvider(provider: IWampConnectorProvider): WampClientBuilder {
        this.connectorProvider = provider
        return this
    }

    /**
     * Assigns additional configuration data for a connection that should be used.<br></br>
     * The type of this configuration data depends on the used [IWampConnectorProvider].<br></br>
     * Depending on the provider this might be null or not.
     * @param configuration The [IWampClientConnectionConfig] that should be used
     * @return The [WampClientBuilder] object
     */
    fun withConnectionConfiguration(configuration: IWampClientConnectionConfig): WampClientBuilder {
        this.connectionConfiguration = configuration
        return this
    }

    /**
     * Adjusts the serializations that this client supports in the session.<br></br>
     * By default a client will have all serializations (JSON, MessagePack).<br></br>
     * The order of the list indicates client preference to the router during
     * negotiation.<br></br>
     * Use this function to adjust the serializations if not all are needed or
     * a different order is desired.
     * @param serializations The set of serializations that the client supports.
     * @return The [WampClientBuilder] object
     */
    @Throws(ApplicationError::class)
    fun withSerializations(serializations: Array<WampSerialization>?): WampClientBuilder {
        this.serializations.clear()
        if (serializations == null) return this // Will throw on build()
        for (serialization in serializations) {
            if (serialization == WampSerialization.Invalid)
                throw ApplicationError(ApplicationError.INVALID_SERIALIZATIONS)
            if (!this.serializations.contains(serialization))
                this.serializations.add(serialization)
        }
        return this
    }

    /**
     * Allows to activate or deactivate the validation of all WAMP Uris according to the
     * strict URI validation rules which are described in the WAMP specification.
     * By default the loose Uri validation rules will be used.
     * @param useStrictUriValidation true if strict Uri validation rules shall be applied,
     * false if loose Uris shall be used.
     * @return The [WampClientBuilder] object
     */
    fun withStrictUriValidation(useStrictUriValidation: Boolean): WampClientBuilder {
        this.useStrictUriValidation = useStrictUriValidation
        return this
    }

    /**
     * Sets whether the client should be closed when an error besides
     * a connection loss happens.<br></br>
     * Other reasons are the reception of invalid messages from the remote
     * or the abortion of the session by the remote.<br></br>
     * When this flag is set to true no reconnect attempts will be performed.
     * <br></br>
     * The default is true. This is to avoid endless reconnects in case of
     * a malfunctioning remote.
     * @param closeOnErrors True if the client should be closed on such
     * errors, false if not.
     * @return The [WampClientBuilder] object
     */
    fun withCloseOnErrors(closeOnErrors: Boolean): WampClientBuilder {
        this.closeOnErrors = closeOnErrors
        return this
    }


    /**
     * Sets the amount of reconnect attempts to perform to a dealer.
     * @param nrReconnects The amount of connects to perform. Must be > 0.
     * @return The [WampClientBuilder] object
     * @throws WampError if the nr of reconnects is negative
     */
    @Throws(ApplicationError::class)
    fun withNrReconnects(nrReconnects: Int): WampClientBuilder {
        if (nrReconnects < 0)
            throw ApplicationError(ApplicationError.INVALID_PARAMETER)
        this.nrReconnects = nrReconnects
        return this
    }

    /**
     * Sets the amount of reconnect attempts to perform to a dealer
     * to infinite.
     * @return The [WampClientBuilder] object
     */
    fun withInfiniteReconnects(): WampClientBuilder {
        this.nrReconnects = -1
        return this
    }

    /**
     * Sets the amount of time that should be waited until a reconnect attempt is performed.<br></br>
     * The default value is [.DEFAULT_RECONNECT_INTERVAL].
     * @param interval The interval that should be waited until a reconnect attempt<br></br>
     * is performed. The interval must be bigger than [.MIN_RECONNECT_INTERVAL].
     * @param unit The unit of the interval
     * @return The [WampClientBuilder] object
     * @throws WampError If the interval is invalid
     */
    @Throws(ApplicationError::class)
    fun withReconnectInterval(interval: Int, unit: TimeUnit): WampClientBuilder {
        val intervalMs = unit.toMillis(interval.toLong())
        if (intervalMs < MIN_RECONNECT_INTERVAL || intervalMs > Integer.MAX_VALUE)
            throw ApplicationError(ApplicationError.INVALID_RECONNECT_INTERVAL)
        this.reconnectInterval = intervalMs.toInt()
        return this
    }

    /**
     * Set the authId to use. If not called, no authId is used.
     * @param authId the authId
     * @return The [WampClientBuilder] object
     */
    fun withAuthId(authId: String): WampClientBuilder {
        this.authId = authId
        return this
    }

    /**
     * Use a specific auth method. Can be called multiple times to specify multiple
     * supported auth methods. If this method is not called, anonymous auth is used.
     * @param authMethod The [ClientSideAuthentication] to add
     * @return The [WampClientBuilder] object
     */
    fun withAuthMethod(authMethod: ClientSideAuthentication): WampClientBuilder {
        this.authMethods.add(authMethod)
        return this
    }

    /**
     * Set custom, pre-configured [ObjectMapper].
     * This instance will be used instead of default for serialization and deserialization.
     * @param objectMapper The [ObjectMapper] instance
     * @return The [WampClientBuilder] object
     */
    fun withObjectMapper(objectMapper: ObjectMapper): WampClientBuilder {
        this.objectMapper = objectMapper
        return this
    }

    companion object {

        /** The default reconnect interval in milliseconds.<br></br>This is set to 5s  */
        val DEFAULT_RECONNECT_INTERVAL = 5000
        /** The minimum reconnect interval in milliseconds.<br></br>This is set to 100ms  */
        val MIN_RECONNECT_INTERVAL = 100
    }

}
