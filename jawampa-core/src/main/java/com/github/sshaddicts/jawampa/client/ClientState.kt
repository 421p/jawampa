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

/**
 * Base class for all internal states that the client can have
 */
interface ClientState {
    /**
     * Is called when a new state is entered.
     * @param lastState The last state that was entered before this state.
     */
    fun onEnter(lastState: ClientState)

    /**
     * Is called when the a state is leaved.
     * @param newState The new state that will be entered
     */
    fun onLeave(newState: ClientState)

    /**
     * Initiates the close process.<br></br>
     * Will be called on [WampClient.close] of the client
     */
    fun initClose()
}