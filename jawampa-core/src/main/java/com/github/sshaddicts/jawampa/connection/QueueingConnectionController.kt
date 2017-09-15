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

import com.github.sshaddicts.jawampa.ApplicationError
import com.github.sshaddicts.jawampa.WampMessages.WampMessage
import com.github.sshaddicts.jawampa.WampSerialization
import java.util.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService

class QueueingConnectionController(
        /** The scheduler on which all state transitions will run  */
        internal val scheduler: ScheduledExecutorService,
        /** The wrapped listener object  */
        internal val connectionListener: IWampConnectionListener) : IConnectionController {

    internal class QueuedMessage(val message: WampMessage, val promise: IWampConnectionPromise<Void>)

    /** Possible states while closing the connection  */
    internal enum class CloseStatus {
        /** Close was not issued  */
        None,
        /** Connection should be closed at the next possible point of time  */
        CloseNow,
        /** Connection should be closed after all already queued messages have been sent  */
        CloseAfterRemaining,
        /** Close was issued but not yet acknowledged  */
        CloseSent,
        /** Close is acknowledged  */
        Closed
    }

    internal var messageSentHandler: ICompletionCallback<Void> = object : ICompletionCallback<Void> {
        override fun onCompletion(future: IWampConnectionFuture<Void>) {
            tryScheduleAction {
                // Dequeue the first element of the queue.
                // Queue might be empty if closed in between
                val first = queuedMessages.poll()
                if (future.isSuccess)
                    first.promise.fulfill(null)
                else {
                    first.promise.reject(future.error())
                }

                /** Whether to close after this call  */
                /** Whether to close after this call  */
                /** Whether to close after this call  */

                /** Whether to close after this call  */
                val sendClose = closeStatus == CloseStatus.CloseNow || closeStatus == CloseStatus.CloseAfterRemaining && queuedMessages.size == 0

                if (sendClose) {
                    // Close the connection now
                    closeStatus = CloseStatus.CloseSent
                    connection.close(true, connectionClosedPromise)
                } else if (queuedMessages.size >= 1) {
                    // There's more to send
                    val nextMessage = queuedMessages.peek().message
                    messageSentPromise.reset(this, null)
                    connection.sendMessage(nextMessage, messageSentPromise)
                }
            }
        }

    }

    private val connectionClosedHandler: ICompletionCallback<Void> = object : ICompletionCallback<Void> {
        override fun onCompletion(future: IWampConnectionFuture<Void>) {
            tryScheduleAction {
                assert(closeStatus == CloseStatus.CloseSent)
                // The connection is now finally closed
                closeStatus = CloseStatus.Closed

                // Complete all pending sends
                while (queuedMessages.size > 0) {
                    val nextMessage = queuedMessages.remove()
                    nextMessage.promise.reject(
                            ApplicationError(ApplicationError.TRANSPORT_CLOSED))
                    // This could theoretically cause side effects.
                    // However it is not valid to call anything on the controller after
                    // close() anyway, so it isn't valid.
                }

                // Forward the result
                if (future.isSuccess)
                    queuedClose!!.fulfill(null)
                else
                    queuedClose!!.reject(future.error())
                queuedClose = null
            }
        }
    }

    /**
     * Promise that will be fulfilled when the underlying connection
     * has sent a single message. The promise will be reused for
     * all messages that will be sent through this controller.
     */
    internal val messageSentPromise = WampConnectionPromise(messageSentHandler, null)

    /**
     * Promise that will be fulfilled when the connection was closed
     * and the close was acknowledged by the underlying connection.
     */
    internal val connectionClosedPromise = WampConnectionPromise(connectionClosedHandler, null)
    /** The wrapped connection object. Must be injected later due to Router design  */
    internal lateinit var connection: IWampConnection

    /** Queued messages  */
    internal var queuedMessages: Deque<QueuedMessage> = ArrayDeque()
    /** Holds the promise that will be fulfilled when the connection was closed  */
    internal var queuedClose: IWampConnectionPromise<Void>? = null

    /** Whether to forward incoming messages or not  */
    private var forwardIncoming = true
    internal var closeStatus = CloseStatus.None

    override fun connectionListener(): IWampConnectionListener {
        return connectionListener
    }

    override fun connection(): IWampConnection {
        return connection
    }

    override fun setConnection(connection: IWampConnection) {
        this.connection = connection
    }

    /**
     * Tries to schedule a runnable on the underlying executor.<br></br>
     * Rejected executions will be suppressed.<br></br>
     * This is useful for cases when the clients EventLoop is shut down before
     * the EventLoop of the underlying connection.
     *
     * @param action The action to schedule.
     */
    private fun tryScheduleAction(action: () -> Unit) {
        try {
            scheduler.submit(action)
        } catch (e: RejectedExecutionException) {
        }

    }

    // IWampConnection members

    override fun serialization(): WampSerialization {
        return connection.serialization()
    }

    override val isSingleWriteOnly: Boolean
        get() = false

    override fun sendMessage(message: WampMessage, promise: IWampConnectionPromise<Void>) {
        if (closeStatus != CloseStatus.None)
            throw IllegalStateException("close() was already called")

        queuedMessages.add(QueuedMessage(message, promise))

        // Check if there is already a send in progress
        if (queuedMessages.size == 1) {
            // We are first in queue. Send immediately
            messageSentPromise.reset(messageSentHandler, null)
            connection.sendMessage(message, messageSentPromise)
        }
    }

    override fun close(sendRemaining: Boolean, promise: IWampConnectionPromise<Void>) {
        if (closeStatus != CloseStatus.None)
            throw IllegalStateException("close() was already called")
        // Mark as closed. No other actions allowed after that
        if (sendRemaining)
            closeStatus = CloseStatus.CloseAfterRemaining
        else
            closeStatus = CloseStatus.CloseNow
        queuedClose = promise

        // Avoid forwarding of new incoming messages
        forwardIncoming = false

        if (queuedMessages.size == 0) {
            // Can immediately start to close
            closeStatus = CloseStatus.CloseSent
            connection.close(true, connectionClosedPromise)
        }
    }

    // IWampConnectionListener methods

    override fun transportClosed() {
        tryScheduleAction {
            // Avoid forwarding more than once
            if (!forwardIncoming) return@tryScheduleAction
            forwardIncoming = false

            connectionListener.transportClosed()
        }
    }

    override fun transportError(cause: Throwable?) {
        tryScheduleAction {
            // Avoid forwarding more than once
            if (!forwardIncoming) return@tryScheduleAction
            forwardIncoming = false

            connectionListener.transportError(cause)
        }
    }

    override fun messageReceived(message: WampMessage) {
        tryScheduleAction {
            // Drop messages that arrive after close
            if (!forwardIncoming) return@tryScheduleAction

            connectionListener.messageReceived(message)
        }
    }
}
