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

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.sshaddicts.jawampa.WampMessages.ErrorMessage
import com.github.sshaddicts.jawampa.WampMessages.YieldMessage
import com.github.sshaddicts.jawampa.client.SessionEstablishedState
import com.github.sshaddicts.jawampa.client.StateController
import com.github.sshaddicts.jawampa.connection.IWampConnectionPromise
import com.github.sshaddicts.jawampa.internal.ArgArrayBuilder
import com.github.sshaddicts.jawampa.internal.UriValidator
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater

/**
 * Holds the arguments for a WAMP remote procedure call and provides methods
 * to send responses to the caller.<br></br>
 * Either [.reply] or
 * [.replyError]} should be called in
 * order to send a positive or negative response back to the caller.
 */
class Request(internal val stateController: StateController, internal val session: SessionEstablishedState,
              internal val requestId: Long, val arguments: ArrayNode?, val keywordArguments: ObjectNode?, val details: ObjectNode?) {

    @Volatile internal var replySent = 0

    /**
     * Send an error message in response to the request.<br></br>
     * If this is called more than once then the following invocations will
     * have no effect. Respones will be only sent once.
     * @param error The ApplicationError that shoul be serialized and sent
     * as an exceptional response. Must not be null.
     */
    @Throws(ApplicationError::class)
    fun replyError(error: ApplicationError?) {
        if (error?.uri == null) throw NullPointerException()
        replyError(error.uri, error.args, error.kwArgs)
    }

    /**
     * Send an error message in response to the request.<br></br>
     * This version of the function will use Jacksons object mapping
     * capabilities to transform the argument objects in a JSON argument
     * array which will be sent as the positional arguments of the call.
     * If keyword arguments are needed then this function can not be used.<br></br>
     * If this is called more than once then the following invocations will
     * have no effect. Respones will be only sent once.
     * @param errorUri The error message that should be sent. This must be a
     * valid WAMP Uri.
     * @param args The positional arguments to sent in the response
     */
    @Throws(ApplicationError::class)
    fun replyError(errorUri: String, vararg args: Any) {
        replyError(errorUri, ArgArrayBuilder.buildArgumentsArray(stateController.clientConfig().objectMapper(), *args), null)
    }

    /**
     * Send an error message in response to the request.<br></br>
     * If this is called more than once then the following invocations will
     * have no effect. Respones will be only sent once.
     * @param errorUri The error message that should be sent. This must be a
     * valid WAMP Uri.
     * @param arguments The positional arguments to sent in the response
     * @param keywordArguments The keyword arguments to sent in the response
     */
    @Throws(ApplicationError::class)
    fun replyError(errorUri: String, arguments: ArrayNode?, keywordArguments: ObjectNode?) {
        val replyWasSent = replySentUpdater.getAndSet(this, 1)
        if (replyWasSent == 1) return

        UriValidator.validate(errorUri, false)

        val msg = ErrorMessage(WampMessages.InvocationMessage.ID,
                requestId, null, errorUri,
                arguments, keywordArguments)

        stateController.tryScheduleAction {
            if (stateController.currentState() !== session) return@tryScheduleAction
            session.connectionController().sendMessage(msg, IWampConnectionPromise.Empty)
        }
    }

    /**
     * Send a normal response to the request.<br></br>
     * If this is called more than once then the following invocations will
     * have no effect. Responses will be only sent once.
     * @param arguments The positional arguments to sent in the response
     * @param keywordArguments The keyword arguments to sent in the response
     */
    fun reply(arguments: ArrayNode?, keywordArguments: ObjectNode?) {
        val replyWasSent = replySentUpdater.getAndSet(this, 1)
        if (replyWasSent == 1) return

        val msg = YieldMessage(requestId, null,
                arguments, keywordArguments)

        stateController.tryScheduleAction {
            if (stateController.currentState() !== session) return@tryScheduleAction
            session.connectionController().sendMessage(msg, IWampConnectionPromise.Empty)
        }
    }

    /**
     * Send a normal response to the request.<br></br>
     * This version of the function will use Jacksons object mapping
     * capabilities to transform the argument objects in a JSON argument
     * array which will be sent as the positional arguments of the call.
     * If keyword arguments are needed then this function can not be used.<br></br>
     * If this is called more than once then the following invocations will
     * have no effect. Respones will be only sent once.
     * @param arguments The positional arguments to sent in the response
     * @param keywordArguments The keyword arguments to sent in the response
     */
    fun reply(vararg args: Any) {
        reply(ArgArrayBuilder.buildArgumentsArray(
                stateController.clientConfig().objectMapper(), *args), null)
    }

    companion object {

        private val replySentUpdater: AtomicIntegerFieldUpdater<Request>

        init {
            replySentUpdater = AtomicIntegerFieldUpdater.newUpdater(Request::class.java, "replySent")
        }
    }

}
