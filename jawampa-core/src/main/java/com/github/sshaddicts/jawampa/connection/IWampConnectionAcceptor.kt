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

/**
 * Provides the means to accept incoming WAMP connections.<br></br>
 * The connection acceptance is split into 2 phases:<br></br>
 *
 *  * In the first step the new connection requests a [IWampConnectionListener] interface
 * from the acceptor. The instance of the interface is not yet attached to the acceptor
 * and can be safely dropped and garbage collected.
 *  * In a second step the new connection is registered together with the received listener
 * at the acceptor.
 *
 */
interface IWampConnectionAcceptor {

    /** Creates a listener for a new incoming connection  */
    fun createNewConnectionListener(): IWampConnectionListener

    /**
     * Requests the acceptor to accept the new incoming connection.<br></br>
     * This **must** be called before any method is called on the [IWampConnectionListener]
     *
     * @param newConnection The connection that is accepted
     * @param connectionListener The listener for the accepted connection.<br></br>
     * This must match the listener that was retrieved with [IWampConnectionAcceptor.createNewConnectionListener]
     */
    fun acceptNewConnection(newConnection: IWampConnection?, connectionListener: IWampConnectionListener?)
}
