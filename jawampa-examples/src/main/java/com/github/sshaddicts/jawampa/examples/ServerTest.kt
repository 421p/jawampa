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

package com.github.sshaddicts.jawampa.examples

import rx.Subscription
import rx.schedulers.Schedulers
import com.github.sshaddicts.jawampa.*
import com.github.sshaddicts.jawampa.transport.netty.NettyWampClientConnectorProvider
import com.github.sshaddicts.jawampa.transport.netty.SimpleWampWebsocketListener
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

class ServerTest {

    private lateinit var addProcSubscription: Subscription
    private lateinit var eventPublication: Subscription
    private var eventSubscription: Subscription? = null
    private var lastEventValue = 0

    fun start() {

        val routerBuilder = WampRouterBuilder()
        val router: WampRouter
        try {
            routerBuilder.addRealm("realm1")
            router = routerBuilder.build()
        } catch (e1: ApplicationError) {
            e1.printStackTrace()
            return
        }

        val serverUri = URI.create("ws://0.0.0.0:8080/ws1")
        val server: SimpleWampWebsocketListener

        val connectorProvider = NettyWampClientConnectorProvider()
        val builder = WampClientBuilder()

        // Build two clients
        val client1: WampClient
        val client2: WampClient
        try {
            server = SimpleWampWebsocketListener(router, serverUri, null)
            server.start()

            builder.withConnectorProvider(connectorProvider)
                    .withUri("ws://localhost:8080/ws1")
                    .withRealm("realm1")
                    .withInfiniteReconnects()
                    .withReconnectInterval(3, TimeUnit.SECONDS)
            client1 = builder.build()
            client2 = builder.build()
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        client1.statusChanged().subscribe({ t1 ->
            println("Session1 status changed to " + t1)

            if (t1 is WampClient.ConnectedState) {
                // Register a procedure
                addProcSubscription = client1.registerProcedure("com.example.add").subscribe { request ->
                    if (request.arguments == null || request.arguments!!.size() != 2
                            || !request.arguments!!.get(0).canConvertToLong()
                            || !request.arguments!!.get(1).canConvertToLong()) {
                        try {
                            request.replyError(ApplicationError(ApplicationError.INVALID_PARAMETER))
                        } catch (e: ApplicationError) {
                            e.printStackTrace()
                        }

                    } else {
                        val a = request.arguments!!.get(0).asLong()
                        val b = request.arguments!!.get(1).asLong()
                        request.reply(a + b)
                    }
                }
            }
        }, { t -> println("Session1 ended with error " + t) }) { println("Session1 ended normally") }

        client2.statusChanged().subscribe({ t1 ->
            println("Session2 status changed to " + t1)

            if (t1 is WampClient.ConnectedState) {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                }

                // Call the procedure
                val result1 = client2.call("com.example.add", Long::class.java, 33, 66)
                result1.subscribe({ t1 -> println("Completed add with result " + t1!!) }) { t1 -> println("Completed add with error " + t1) }

                // Call the procedure with invalid values
                val result2 = client2.call("com.example.add", Long::class.java, 1, "dafs")
                result2.subscribe({ t1 -> println("Completed add with result " + t1!!) }) { t1 -> println("Completed add with error " + t1) }

                eventSubscription = client2.makeSubscription("test.event", String::class.java)
                        .subscribe({ t1 -> println("Received event test.event with value " + t1) }, { t1 -> println("Completed event test.event with error " + t1) }) { println("Completed event test.event") }

                // Subscribe on the topic

            }
        }, { t -> println("Session2 ended with error " + t) }) { println("Session2 ended normally") }

        client1.open()
        client2.open()

        // Publish an event regularly
        eventPublication = Schedulers.computation().createWorker().schedulePeriodically({
            client1.publish("test.event", listOf("Hello " + lastEventValue))
            lastEventValue++
        }, eventInterval.toLong(), eventInterval.toLong(), TimeUnit.MILLISECONDS)

        waitUntilKeypressed()
        println("Stopping subscription")
        if (eventSubscription != null)
            eventSubscription!!.unsubscribe()

        waitUntilKeypressed()
        println("Stopping publication")
        eventPublication.unsubscribe()

        waitUntilKeypressed()
        println("Closing router")
        router.close().toBlocking().last()
        server.stop()

        waitUntilKeypressed()
        println("Closing the client 1")
        client1.close().toBlocking().last()

        waitUntilKeypressed()
        println("Closing the client 2")
        client2.close().toBlocking().last()
    }

    private fun waitUntilKeypressed() {
        try {
            System.`in`.read()
            while (System.`in`.available() > 0) {
                System.`in`.read()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    companion object {

        @JvmStatic
        fun main(args: Array<String>) {
            ServerTest().start()
        }

        internal val eventInterval = 2000
    }

}
