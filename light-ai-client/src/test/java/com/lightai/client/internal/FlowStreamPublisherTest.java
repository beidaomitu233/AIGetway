package com.lightai.client.internal;

import com.lightai.client.StreamEvent;
import com.lightai.client.StreamEventType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class FlowStreamPublisherTest {

    @Test
    void shouldEnforceSingleSubscriber() {
        FlowStreamPublisher publisher = new FlowStreamPublisher();

        List<StreamEvent> events1 = new ArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(10); }
            @Override public void onNext(StreamEvent item) { events1.add(item); }
            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });

        AtomicReference<Throwable> secondSubscriberError = new AtomicReference<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) {}
            @Override public void onNext(StreamEvent item) {}
            @Override public void onError(Throwable throwable) { secondSubscriberError.set(throwable); }
            @Override public void onComplete() {}
        });

        assertThat(secondSubscriberError.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只允许一个 Subscriber");
    }

    @Test
    void shouldRespectBackpressure() {
        FlowStreamPublisher publisher = new FlowStreamPublisher();
        List<StreamEvent> received = new ArrayList<>();
        AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subRef.set(subscription);
            }

            @Override
            public void onNext(StreamEvent item) {
                received.add(item);
            }

            @Override public void onError(Throwable throwable) {}
            @Override public void onComplete() {}
        });

        StreamEvent e1 = StreamEvent.delta("1", "A");
        StreamEvent e2 = StreamEvent.delta("2", "B");
        StreamEvent e3 = StreamEvent.delta("3", "C");

        publisher.submit(e1);
        publisher.submit(e2);
        publisher.submit(e3);

        // Before request, no events received
        assertThat(received).isEmpty();

        // Request 1
        subRef.get().request(1);
        assertThat(received).hasSize(1).containsExactly(e1);

        // Request 2 more
        subRef.get().request(2);
        assertThat(received).hasSize(3).containsExactly(e1, e2, e3);
    }

    @Test
    void shouldFailOnInvalidRequestCount() {
        FlowStreamPublisher publisher = new FlowStreamPublisher();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(0);
            }

            @Override public void onNext(StreamEvent item) {}
            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
            }
            @Override public void onComplete() {}
        });

        assertThat(errorRef.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须大于 0");
    }

    @Test
    void shouldHandleCancellationWithoutDoneOrOnComplete() {
        AtomicBoolean cancelCallbackTriggered = new AtomicBoolean(false);
        FlowStreamPublisher publisher = new FlowStreamPublisher(() -> cancelCallbackTriggered.set(true));

        List<StreamEvent> received = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subRef.set(subscription);
                subscription.request(10);
            }

            @Override
            public void onNext(StreamEvent item) {
                received.add(item);
            }

            @Override public void onError(Throwable throwable) {}

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        publisher.submit(StreamEvent.start("trace-1"));
        assertThat(received).hasSize(1);

        // Cancel
        subRef.get().cancel();
        assertThat(cancelCallbackTriggered.get()).isTrue();
        assertThat(publisher.isCancelled()).isTrue();

        // Further submits should not be accepted
        boolean submitted = publisher.submit(StreamEvent.delta("trace-1", "dropped"));
        assertThat(submitted).isFalse();

        // Complete should not trigger onComplete because it was cancelled
        publisher.complete();
        assertThat(completed.get()).isFalse();
        assertThat(received).hasSize(1);
    }

    @Test
    void shouldNeverCallOnCompleteAfterOnError() {
        FlowStreamPublisher publisher = new FlowStreamPublisher();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(10);
            }

            @Override public void onNext(StreamEvent item) {}

            @Override
            public void onError(Throwable throwable) {
                errorRef.set(throwable);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        publisher.error(new RuntimeException("Simulated stream failure"));
        publisher.complete();

        assertThat(errorRef.get()).isNotNull().hasMessage("Simulated stream failure");
        assertThat(completed.get()).isFalse();
    }
}