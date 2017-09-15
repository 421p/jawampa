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

import rx.Scheduler
import rx.Subscription
import rx.functions.Action0
import rx.internal.schedulers.ScheduledAction
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription
import rx.subscriptions.Subscriptions
import java.util.concurrent.TimeUnit

/**
 * A single threaded scheduler based on the RX computation scheduler.
 */
class SingleThreadedComputationScheduler : Scheduler() {
    /**
     * A single threaded worker from the computation threadpool on which
     * we we will base our workers
     */
    internal val innerWorker: Worker = Schedulers.computation().createWorker()

    override fun createWorker(): Worker {
        return SchedulerWorker(innerWorker)
    }

    private class SchedulerWorker(internal val innerWorker: Worker) : Worker() {
        internal val innerSubscription = CompositeSubscription()

        override fun unsubscribe() {
            innerSubscription.unsubscribe()
        }

        override fun isUnsubscribed(): Boolean {
            return innerSubscription.isUnsubscribed
        }

        override fun schedule(action: Action0): Subscription {
            return schedule(action, 0, null)
        }

        override fun schedule(action: Action0, delayTime: Long, unit: TimeUnit?): Subscription {
            if (innerSubscription.isUnsubscribed) {
                // don't schedule, we are unsubscribed
                return Subscriptions.empty()
            }

            val s = innerWorker.schedule(action, delayTime, unit) as ScheduledAction
            innerSubscription.add(s)
            s.addParent(innerSubscription)
            return s
        }
    }
}