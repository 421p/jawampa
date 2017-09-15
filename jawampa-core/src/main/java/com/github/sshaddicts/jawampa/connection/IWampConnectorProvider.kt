/*
 * Copyright 2015 Matthias Einwag
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

package com.github.sshaddicts.jawampa.connection

import com.github.sshaddicts.jawampa.WampSerialization
import java.net.URI
import java.util.concurrent.ScheduledExecutorService

/**
 * Provides connectors that are used by WAMP clients to connect to servers.
 */
interface IWampConnectorProvider {

    /**
     * Creates and returns a scheduler for the client.<br></br>
     * The scheduler is suitable as a scheduler for the client transports which
     * are created by the factory
     */
    fun createScheduler(): ScheduledExecutorService

    /**
     * Create a connector that can be used by a client to connect to a server later on.
     * @param uri The uri to which the client should connect
     * @param configuration Additional configuration information for the connection.<br></br>
     * The type of the configuration depends on the [IWampConnectorProvider].
     * @param serializations The serializations that the connection should support in
     * preferred order.<br></br>
     * Must not be null.
     * @return A connector which can be used by the client to connect to the server.
     * @throws Exception If no suitable connector can be created with this configuration
     */
    @Throws(Exception::class)
    fun createConnector(uri: URI,
                        configuration: IWampClientConnectionConfig,
                        serializations: List<WampSerialization>): IWampConnector
}
