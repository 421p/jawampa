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

object MessageType {

    val HELLO: Byte = 1
    val WELCOME: Byte = 2
    val ABORT: Byte = 3
    val CHALLENGE: Byte = 4
    val AUTHENTICATE: Byte = 5
    val GOODBYE: Byte = 6
    val HEARTBEAT: Byte = 7
    val ERROR: Byte = 8

    val PUBLISH: Byte = 16
    val PUBLISHED: Byte = 17

    val SUBSCRIBE: Byte = 32
    val SUBSCRIBED: Byte = 33
    val UNSUBSCRIBE: Byte = 34
    val UNSUBSCRIBED: Byte = 35
    val EVENT: Byte = 36

    val CALL: Byte = 48
    val CANCEL: Byte = 49
    val RESULT: Byte = 50

    val REGISTER: Byte = 64
    val REGISTERED: Byte = 65
    val UNREGISTER: Byte = 66
    val UNREGISTERED: Byte = 67
    val INVOCATION: Byte = 68
    val INTERRUPT: Byte = 69
    val YIELD: Byte = 70

}
