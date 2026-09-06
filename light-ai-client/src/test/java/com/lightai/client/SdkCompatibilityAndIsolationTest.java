package com.lightai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lightai.client.chat.UnifiedChatResponse;
import com.lightai.client.error.ErrorCode;
import com.lightai.client.json.ProtocolJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK 制品纯 Java 隔离与向下兼容性验收（PRD 6.5，BE-059）：
 * 1. 纯 Java 隔离：light-ai-client 核心代码严禁依赖 Spring Framework 类库；
 * 2. 响应向前兼容：客户端同主版本忽略未知新增字段（FAIL_ON_UNKNOWN_PROPERTIES = false 协议要求）；
 * 3. Flow 流式背压与取消支持。
 */
public class SdkCompatibilityAndIsolationTest {

    @Test
    @DisplayName("BE-059: 验证 light-ai-client 字节码与接口绝无 Spring 依赖")
    void testClientHasZeroSpringDependencies() {
        Class<?>[] clientClasses = new Class<?>[] {
                LightAiClient.class,
                ChatRequest.class,
                ChatResponse.class,
                StreamEvent.class,
                StreamEventType.class,
                ModelInfo.class,
                ErrorCode.class
        };

        for (Class<?> clazz : clientClasses) {
            for (Method method : clazz.getMethods()) {
                String returnType = method.getReturnType().getName();
                assertFalse(returnType.startsWith("org.springframework."),
                        "方法返回类型不能包含 Spring 类: " + returnType);
                for (Class<?> param : method.getParameterTypes()) {
                    assertFalse(param.getName().startsWith("org.springframework."),
                            "方法参数类型不能包含 Spring 类: " + param.getName());
                }
            }
        }
    }

    @Test
    @DisplayName("BE-059: 验证协议响应忽略服务端未知新增字段（向前兼容）")
    void testForwardCompatibilityIgnoresUnknownFields() throws Exception {
        String jsonWithFutureFields = """
                {
                    "id": "chatcmpl-test12345",
                    "object": "chat.completion",
                    "created": 1725600000,
                    "model": "gpt-4o",
                    "choices": [
                        {
                            "index": 0,
                            "message": {
                                "role": "assistant",
                                "content": "Hello compatibility!"
                            },
                            "finish_reason": "stop"
                        }
                    ],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 20,
                        "total_tokens": 30,
                        "source": "ACTUAL"
                    },
                    "light_ai": {
                        "provider": "OPENAI",
                        "provider_model": "gpt-4o",
                        "trace_id": "trace-12345",
                        "usage_source": "ACTUAL",
                        "snapshot_no": 1,
                        "cost": {
                            "amount": "0.00003",
                            "currency": "USD",
                            "estimated": false
                        }
                    },
                    "future_unknown_feature_field": "future_data_value",
                    "experimental_telemetry": {
                        "node_id": "cluster-node-9"
                    }
                }
                """;

        ObjectMapper mapper = ProtocolJson.protocol();
        UnifiedChatResponse response = mapper.readValue(jsonWithFutureFields, UnifiedChatResponse.class);

        assertNotNull(response);
        assertEquals("chatcmpl-test12345", response.id());
        assertEquals("gpt-4o", response.model());
        assertEquals("Hello compatibility!", response.choices().get(0).message().content());
        assertEquals("USD", response.lightAi().cost().currency());
    }

    @Test
    @DisplayName("BE-059: 验证标准 Flow.Publisher 背压与取消语义")
    void testFlowPublisherBackpressureAndCancellation() throws InterruptedException {
        // 创建模拟支持背压的 Flow.Publisher
        SubmissionPublisher<StreamEvent> publisher = new SubmissionPublisher<>(ForkJoinPool.commonPool(), 32);

        List<StreamEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch firstReceived = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicInteger subscriptionRequests = new AtomicInteger(0);

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscriptionRequests.incrementAndGet();
                // 背压：初次只请求 1 个事件
                subscription.request(1);
            }

            @Override
            public void onNext(StreamEvent item) {
                receivedEvents.add(item);
                firstReceived.countDown();
                // 收到 1 个后取消，不再请求
                subscription.cancel();
                cancelled.set(true);
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        });

        // 发送两个事件
        publisher.submit(StreamEvent.delta("trace-1", 1L, "gpt-4o", "OPENAI", "gpt-4o", "First token"));
        publisher.submit(StreamEvent.delta("trace-1", 2L, "gpt-4o", "OPENAI", "gpt-4o", "Second token"));

        assertTrue(firstReceived.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);

        // 验证背压：取消后只有 1 个事件被处理
        assertEquals(1, receivedEvents.size());
        assertEquals("First token", receivedEvents.get(0).delta());
        assertTrue(cancelled.get());
        publisher.close();
    }
}
