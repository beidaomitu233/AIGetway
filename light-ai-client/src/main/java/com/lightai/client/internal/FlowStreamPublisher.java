package com.lightai.client.internal;

import com.lightai.client.StreamEvent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 响应式流发布者（BE-052，4.6.2.5）：
 * 1. 单一订阅者：每个 Publisher 仅允许一个 Subscriber；
 * 2. 背压控制：最大缓冲 <= 32 项；
 * 3. request(n)：n <= 0 抛出 IllegalArgumentException；
 * 4. cancel：单次终止，不发送 DONE，不调用 onComplete；
 * 5. onError 之后绝不调用 onComplete。
 */
public class FlowStreamPublisher implements Flow.Publisher<StreamEvent> {

    public static final int BUFFER_CAPACITY = 32;

    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final Runnable cancelCallback;
    private volatile StreamSubscription subscription;

    public FlowStreamPublisher() {
        this(null);
    }

    public FlowStreamPublisher(Runnable cancelCallback) {
        this.cancelCallback = cancelCallback;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super StreamEvent> subscriber) {
        Objects.requireNonNull(subscriber, "subscriber 不能为空");
        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) {}
                @Override public void cancel() {}
            });
            subscriber.onError(new IllegalStateException("每个 Publisher 实例只允许一个 Subscriber"));
            return;
        }

        this.subscription = new StreamSubscription(subscriber, cancelCallback);
        subscriber.onSubscribe(subscription);
    }

    public boolean submit(StreamEvent event) {
        if (subscription == null) {
            return false;
        }
        return subscription.submit(event);
    }

    public void complete() {
        if (subscription != null) {
            subscription.complete();
        }
    }

    public void error(Throwable throwable) {
        if (subscription != null) {
            subscription.error(throwable);
        }
    }

    public boolean isCancelled() {
        return subscription != null && subscription.isCancelled();
    }

    private static class StreamSubscription implements Flow.Subscription {

        private final Flow.Subscriber<? super StreamEvent> subscriber;
        private final Runnable cancelCallback;
        private final Queue<StreamEvent> buffer = new ArrayDeque<>(BUFFER_CAPACITY);
        private final Object lock = new Object();

        private long demand = 0;
        private volatile boolean cancelled = false;
        private volatile boolean sourceCompleted = false;
        private volatile boolean terminated = false;

        StreamSubscription(Flow.Subscriber<? super StreamEvent> subscriber, Runnable cancelCallback) {
            this.subscriber = subscriber;
            this.cancelCallback = cancelCallback;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                boolean emitError = false;
                synchronized (lock) {
                    if (!cancelled && !terminated) {
                        cancelled = true;
                        terminated = true;
                        emitError = true;
                    }
                }
                if (emitError) {
                    if (cancelCallback != null) {
                        try {
                            cancelCallback.run();
                        } catch (Exception ignored) {
                        }
                    }
                    subscriber.onError(new IllegalArgumentException("request(n) 的参数 n 必须大于 0，实际为: " + n));
                }
                return;
            }

            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                demand = (demand + n < 0) ? Long.MAX_VALUE : (demand + n);
                drain();
            }
        }

        @Override
        public void cancel() {
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                buffer.clear();
            }
            if (cancelCallback != null) {
                try {
                    cancelCallback.run();
                } catch (Exception ignored) {
                }
            }
        }

        boolean submit(StreamEvent event) {
            synchronized (lock) {
                if (cancelled || terminated) {
                    return false;
                }

                // 若缓冲区满（>= 32），阻塞等待或直到取消/被消费
                while (buffer.size() >= BUFFER_CAPACITY && !cancelled && !terminated) {
                    try {
                        lock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }

                if (cancelled || terminated) {
                    return false;
                }

                buffer.offer(event);
                drain();
                return true;
            }
        }

        void complete() {
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                sourceCompleted = true;
                drain();
            }
        }

        void error(Throwable t) {
            synchronized (lock) {
                if (cancelled || terminated) {
                    return;
                }
                terminated = true;
                buffer.clear();
            }
            subscriber.onError(t);
        }

        boolean isCancelled() {
            return cancelled;
        }

        private void drain() {
            // 在锁内调用
            while (!cancelled && !terminated && demand > 0 && !buffer.isEmpty()) {
                StreamEvent event = buffer.poll();
                demand--;
                lock.notifyAll(); // 通知等待入队的生产者
                try {
                    subscriber.onNext(event);
                } catch (Throwable t) {
                    cancelled = true;
                    terminated = true;
                    buffer.clear();
                    subscriber.onError(t);
                    return;
                }
            }

            if (!cancelled && !terminated && sourceCompleted && buffer.isEmpty()) {
                terminated = true;
                subscriber.onComplete();
            }
        }
    }
}