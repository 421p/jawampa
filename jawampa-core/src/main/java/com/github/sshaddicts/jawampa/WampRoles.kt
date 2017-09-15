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

/**
 * Possible roles for WAMP peers
 */
enum class WampRoles(private val stringValue: String) {

    Callee("callee"),
    Caller("caller"),
    Publisher("publisher"),
    Subscriber("subscriber"),
    Dealer("dealer"),
    Broker("broker");

    override fun toString(): String {
        return stringValue
    }

    companion object {

        fun fromString(role: String?): WampRoles? {
            if (role == null)
                return null
            else if (role == "callee")
                return Callee
            else if (role == "caller")
                return Caller
            else if (role == "publisher")
                return Publisher
            else if (role == "subscriber")
                return Subscriber
            else if (role == "dealer")
                return Dealer
            else if (role == "broker") return Broker
            return null
        }
    }

}
