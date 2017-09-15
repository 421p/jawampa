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

package com.github.sshaddicts.jawampa.internal

import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class Promise<V> {

    internal var value: V? = null
    internal var done = false
    internal var error: ExecutionException? = null
    internal val mutex = Object()

    fun resolve(value: V?) {
        synchronized(mutex) {
            if (done)
                throw RuntimeException("Promise resolved multiple times!")
            this.value = value
            this.done = true
            mutex.notifyAll()
        }
    }

    fun resolveWithError(e: ExecutionException) {
        synchronized(mutex) {
            if (done)
                throw RuntimeException("Promise resolved multiple times!")
            this.error = e
            this.done = true
            mutex.notifyAll()
        }
    }

    val future: java.util.concurrent.Future<V>
        get() = object : Future<V> {
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                return false
            }

            override fun isCancelled(): Boolean {
                return false
            }

            override fun isDone(): Boolean {
                synchronized(mutex) {
                    return done
                }
            }

            @Throws(InterruptedException::class, ExecutionException::class)
            override fun get(): V {
                synchronized(mutex) {
                    while (!done) mutex.wait()
                    if (error != null) throw error!!
                    return value!!
                }
            }

            @Throws(InterruptedException::class, ExecutionException::class, TimeoutException::class)
            override fun get(timeout: Long, unit: TimeUnit): V {
                synchronized(mutex) {
                    while (!done) unit.timedWait(mutex, timeout)
                    if (error != null) throw error!!
                    return value!!
                }
            }
        }

}
