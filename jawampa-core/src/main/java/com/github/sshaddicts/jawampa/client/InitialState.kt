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


/**
 * The client is just initialized.<br></br>
 * There was no attempt to connect.
 */
class InitialState internal constructor(private val stateController: StateController) : ClientState {

    override fun onEnter(lastState: ClientState) {

    }

    override fun onLeave(newState: ClientState) {

    }

    override fun initClose() {
        val nextState = DisconnectedState(stateController, null)
        stateController.setState(nextState)
    }
}