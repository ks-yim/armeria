/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License
 */

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.internal.common.kotlin.FlowCollectingPublisher
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.test.StepVerifier
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))

class FlowCollectingPublisherTest {
    @Test
    fun test_shouldCompleteAfterConsumingAllElements() {
        val pub = FlowCollectingPublisher(flowOf(1, 2), ctx)
        StepVerifier.create(pub)
            .expectNext(1, 2)
            .verifyComplete()
    }

    @Test
    fun test_backPressureOnFlow() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        val executor = Executors.newSingleThreadScheduledExecutor()

        FlowCollectingPublisher(
            flow {
                (0 until 3).forEach {
                    emit(it)
                    queue.add(it)
                }
            },
            ctx
        ).subscribe(object : Subscriber<Int> {
            private lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {
                executor.schedule({ subscription.request(1L) }, 2, TimeUnit.SECONDS)
            }

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })

        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(0)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(1)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(2)
    }

    @Test
    fun test_backPressuresWithBuffer() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        val executor = Executors.newSingleThreadScheduledExecutor()

        FlowCollectingPublisher(
            flow {
                (0 until 7).forEach {
                    emit(it)
                    queue.add(it)
                }
            }.buffer(capacity = 1),
            ctx
        ).subscribe(object : Subscriber<Int> {
            private lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {
                executor.schedule({ subscription.request(1L) }, 2, TimeUnit.SECONDS)
            }

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })

        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(0)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(1)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(2)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(3)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(4)
    }

    @Test
    fun test_emitsOnErrorSignalUponUpstreamCancellation() {
        val pub = FlowCollectingPublisher(
            flow {
                emit("onNext")
                currentCoroutineContext().cancel()
            },
            ctx
        )
        StepVerifier.create(pub)
            .expectNext("onNext")
            .expectError(CancellationException::class.java)
            .verify()
    }

    @Test
    fun test_errorAfterCancellationIsNotSignaled() {
        val pub = FlowCollectingPublisher(
            flow {
                try {
                    emit("onNext")
                    emit("onNext")
                } catch (t: CancellationException) {
                    // do nothing
                } finally {
                    throw RuntimeException()
                }
            },
            ctx
        )
        StepVerifier.create(pub)
            .expectSubscription()
            .expectNext("onNext")
            .thenCancel()
            .verify()
    }

    @Test
    fun test_propagatesCoroutineContext() {
        val context = CoroutineName("custom-context")
        val coroutineNameCaptor = AtomicReference<CoroutineName>()

        FlowCollectingPublisher(
            flow = flow {
                coroutineNameCaptor.set(currentCoroutineContext()[CoroutineName])
                emit(1)
            },
            ctx = ctx,
            context = context
        ).subscribe(object : Subscriber<Int> {
            override fun onSubscribe(s: Subscription) {}

            override fun onNext(t: Int) {}

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })
        await().untilAsserted { assertThat(coroutineNameCaptor.get()).isEqualTo(context) }
    }
}
