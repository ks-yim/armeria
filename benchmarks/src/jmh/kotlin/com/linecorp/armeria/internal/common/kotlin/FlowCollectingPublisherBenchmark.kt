package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asPublisher
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.CountDownLatch

@State(Scope.Benchmark)
@Warmup(iterations = 1)
@Suppress("unused")
open class FlowCollectingPublisherBenchmark {

    private val ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
    private lateinit var flow: Flow<Long>

    @Setup
    open fun setup() {
        flow = flow {
            (0L until 10000L).onEach {
                emit(it)
            }
        }
        (0 until 50).onEach {
            ctx.eventLoop().execute {}
        }
        Thread.sleep(2000L)
    }

    @Benchmark
    open fun asPublisher(bh: Blackhole) {
        val latch = CountDownLatch(1)
        flow.asPublisher(newCoroutineCtx(ctx.eventLoop(), ctx))
            .subscribe(object : Subscriber<Long> {
                override fun onSubscribe(s: Subscription) {
                    s.request(Long.MAX_VALUE)
                }

                override fun onNext(t: Long) {
                }

                override fun onError(t: Throwable) {
                }

                override fun onComplete() {
                    latch.countDown()
                }
            })
        latch.await()
    }

    @Benchmark
    open fun flowCollectingPublisher(bh: Blackhole) {
        val latch = CountDownLatch(1)
        flow.asPublisher(ctx.eventLoop(), ctx)
            .subscribe(object : Subscriber<Long> {
                override fun onSubscribe(s: Subscription) {
                    s.request(Long.MAX_VALUE)
                }

                override fun onNext(t: Long) {
                }

                override fun onError(t: Throwable) {
                }

                override fun onComplete() {
                    latch.countDown()
                }
            })
        latch.await()
    }
}
