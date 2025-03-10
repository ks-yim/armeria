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
 * under the License.
 */

package com.linecorp.armeria.internal.common.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.stream.DefaultStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.ImmediateEventExecutor;

class RecoverableStreamMessageTest {
    @Test
    void noError() {
        final StreamMessage<Integer> recoverable =
                StreamMessage.of(1, 2, 3).recoverAndResume(cause -> StreamMessage.of(4));
        assertThat(recoverable.collect().join()).contains(1, 2, 3);
    }

    @Test
    void failedStream() {
        final StreamMessage<Integer> recoverable =
                StreamMessage.<Integer>aborted(ClosedStreamException.get())
                             .recoverAndResume(cause -> StreamMessage.of(1, 2, 3));
        assertThat(recoverable.collect().join()).contains(1, 2, 3);
    }

    @Test
    void resumeOnError() {
        final DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        final StreamMessage<Integer> recoverable = stream.recoverAndResume(cause -> StreamMessage.of(4, 5, 6));
        stream.write(1);
        stream.write(2);
        stream.write(3);
        stream.close(ClosedStreamException.get());
        assertThat(recoverable.collect().join()).contains(1, 2, 3, 4, 5, 6);
    }

    @CsvSource({ "true", "false" })
    @ParameterizedTest
    void shouldNotResumeOnAbortion(boolean abort) {
        final DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        final StreamMessage<Integer> aborted = stream.recoverAndResume(cause -> StreamMessage.of(4, 5, 6));
        stream.write(1);
        stream.write(2);
        stream.write(3);
        final AtomicInteger expected = new AtomicInteger(1);
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        aborted.subscribe(new Subscriber<Integer>() {

            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Integer item) {
                assertThat(expected.getAndIncrement()).isEqualTo(item);
                if (item == 2) {
                    if (abort) {
                        stream.abort();
                    } else {
                        subscription.cancel();
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        }, ImmediateEventExecutor.INSTANCE, SubscriptionOption.NOTIFY_CANCELLATION);

        final Class<? extends Throwable> expectedCause;
        if (abort) {
            expectedCause = AbortedStreamException.class;
        } else {
            expectedCause = CancelledSubscriptionException.class;
        }
        await().untilAsserted(() -> {
            assertThat(causeRef.get()).isInstanceOf(expectedCause);
        });

        assertThat(expected).hasValue(3);
    }

    @Test
    void backPressure() {
        final DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        final StreamMessage<Integer> recoverable = stream.recoverAndResume(cause -> StreamMessage.of(4, 5, 6));
        stream.write(1);
        stream.write(2);
        stream.write(3);
        stream.close(ClosedStreamException.get());
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicInteger expected = new AtomicInteger(1);

        recoverable.subscribe(new Subscriber<Integer>() {

            int demand;
            @Nullable
            private Subscription subscription;

            @Override
            public void onSubscribe(Subscription s) {
                subscription = s;
                demand++;
                subscription.request(1);
            }

            @Override
            public void onNext(Integer item) {
                demand--;
                assertThat(demand).isZero();
                assertThat(expected.getAndIncrement()).isEqualTo(item);
                demand++;
                subscription.request(1);
            }

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        }, ImmediateEventExecutor.INSTANCE);

        await().untilTrue(completed);
        assertThat(expected).hasValue(7);
    }

    @Test
    void disallowResuming_emtpyStream() {
        final StreamMessage<Integer> aborted = StreamMessage.aborted(ClosedStreamException.get());
        final StreamMessage<Integer> recoverable = new RecoverableStreamMessage<>(
                aborted, cause -> StreamMessage.of(1, 2, 3), /* allowResuming */ false);
        assertThat(recoverable.collect().join()).contains(1, 2, 3);
    }

    @Test
    void disallowResuming_nonEmtpyStream() {
        // Resume is disabled.
        // The fallback function should be not invoked if some items are written before an error occurs.
        final DefaultStreamMessage<Integer> stream = new DefaultStreamMessage<>();
        stream.write(1);
        stream.write(2);
        stream.write(3);
        final ClosedStreamException cause = ClosedStreamException.get();
        stream.close(cause);
        final StreamMessage<Integer> recoverable = new RecoverableStreamMessage<>(
                stream, unused -> StreamMessage.of(4, 5, 6), /* allowResuming */ false);

        final AtomicInteger expected = new AtomicInteger(1);
        final AtomicReference<Throwable> causeRef = new AtomicReference<>();
        recoverable.subscribe(new Subscriber<Integer>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Integer item) {
                assertThat(expected.getAndIncrement()).isEqualTo(item);
            }

            @Override
            public void onError(Throwable t) {
                causeRef.set(t);
            }

            @Override
            public void onComplete() {}
        });

        await().untilAsserted(() -> assertThat(causeRef).hasValue(cause));
        assertThat(expected).hasValue(4);
    }

    @Test
    void multipleFallback() {
        final IllegalStateException cause1 = new IllegalStateException("1");
        final IllegalStateException cause2 = new IllegalStateException("2");
        final IllegalStateException cause3 = new IllegalStateException("3");
        final StreamMessage<Object> neverFail =
                StreamMessage.aborted(cause1)
                             .recoverAndResume(cause -> {
                                 assertThat(cause).isSameAs(cause1);
                                 return StreamMessage.aborted(cause2);
                             })
                             .recoverAndResume(cause -> {
                                 assertThat(cause).isSameAs(cause2);
                                 return StreamMessage.aborted(cause3);
                             })
                             .recoverAndResume(cause -> {
                                 assertThat(cause).isSameAs(cause3);
                                 return StreamMessage.of(1, 2, 3);
                             });
        assertThat(neverFail.collect().join()).containsExactly(1, 2, 3);
    }

    @Test
    void recoverableHttpResponse() {
        final HttpResponse failedResponse = HttpResponse.ofFailure(ClosedStreamException.get());
        final HttpResponse recovered = failedResponse.recover(cause -> HttpResponse.of("fallback"));
        final AggregatedHttpResponse response = recovered.aggregate().join();
        assertThat(response.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("fallback");

        final HttpResponseWriter failedResponse2 = HttpResponse.streaming();
        failedResponse2.write(ResponseHeaders.of(HttpStatus.OK));
        final HttpResponse transformed =
                failedResponse2.mapHeaders(headers -> {
                    throw ClosedStreamException.get();
                });
        final HttpResponse recovered2 = transformed.recover(cause -> HttpResponse.of("fallback"));
        final AggregatedHttpResponse response2 = recovered2.aggregate().join();
        assertThat(response2.headers().status()).isEqualTo(HttpStatus.OK);
        assertThat(response2.contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void nonRecoverableHttpResponse() {
        final HttpResponseWriter failedResponse = HttpResponse.streaming();
        failedResponse.write(ResponseHeaders.of(HttpStatus.OK));
        failedResponse.write(HttpData.ofUtf8("hello"));
        final ClosedStreamException cause1 = ClosedStreamException.get();
        failedResponse.close(cause1);
        final HttpResponse recovered = failedResponse.recover(unused -> HttpResponse.of("fallback"));
        assertThatThrownBy(() -> recovered.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(ClosedStreamException.class);
    }
}
