package com.galaxy.auratrader.llm.chat;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.galaxy.auratrader.llm.tool.*;
import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class Chatter {
    private final DeepSeekClient deepSeekClient;
    private final AIToolRegistry toolRegistry;


    public void chat() {

        Flux<ChatCompletionResponse> responseFlux = deepSeekClient.chatFluxCompletion("Hello World");
        responseFlux.subscribe(response -> {
//            System.out.println("Received chunk: " + response.choices().get(0).delta().reasoningContent());
//            System.out.println("Received chunk: " + response.choices().get(0).delta().content());
            Delta delta = response.choices().get(0).delta();
            if (delta.reasoningContent() != null) {
                System.out.println("Received reasoning content chunk: " + delta.reasoningContent());
            }
            if (delta.content() != null) {
                System.out.println("Received content chunk: " + delta.content());
            }
        });


        // wait for a while to let the flux complete
        try {
            Thread.sleep(50000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sleeping in chat()", e);
        }

//        SyncOrAsyncOrStreaming<String> helloWorld = deepSeekClient.chatCompletion("Hello World");
//        String execute = helloWorld.execute();
//        System.out.println("Response: " + execute);
    }

    // 新增方法：返回流式的 ChatCompletionResponse，供 UI 订阅和流式渲染
    public Flux<ChatCompletionResponse> streamChatFlux(String prompt) {
        return deepSeekClient.chatFluxCompletion(prompt);
    }


    public void toolCall(String prompt, boolean enableThinking) {
        List<ToolMetadata> allToolMetadata = toolRegistry.getAllToolMetadata();
        List<Tool> tools = convertToTools(allToolMetadata);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(enableThinking?ChatCompletionModel.DEEPSEEK_REASONER:ChatCompletionModel.DEEPSEEK_CHAT)
                .addUserMessage(prompt)
                .tools(tools)  // 添加工具函数
                .build();
        recursiveToolCall(request);
    }

    public Flux<MessageEvent> streamToolCall(String prompt, boolean enableThinking) {
        List<ToolMetadata> allToolMetadata = toolRegistry.getAllToolMetadata();
        List<Tool> tools = convertToTools(allToolMetadata);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(enableThinking ? ChatCompletionModel.DEEPSEEK_REASONER : ChatCompletionModel.DEEPSEEK_CHAT)
                .addUserMessage(prompt)
                .tools(tools)
                .build();

        Sinks.Many<MessageEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Run orchestration on a boundedElastic thread so we don't block reactor Netty threads or callers.
        Mono.fromRunnable(() -> orchestrateToolCall(request, sink))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return sink.asFlux();
    }

    private void orchestrateToolCall(ChatCompletionRequest request, Sinks.Many<MessageEvent> sink) {
        AtomicLong seq = new AtomicLong(0L);
        try {
            while (true) {
                CountDownLatch countDownLatch = new CountDownLatch(1);

                StringBuilder reasoningText = new StringBuilder();
                StringBuilder contentText = new StringBuilder();
                Map<Integer, ToolCallContext> toolCallMap = new LinkedHashMap<>();
                StringBuilder finishReason = new StringBuilder();

                deepSeekClient.chatFluxCompletion(request).subscribe(
                        response -> {
                            ChatCompletionChoice choice = response.choices().get(0);
                            Delta delta = response.choices().get(0).delta();

                            if (delta.reasoningContent() != null) {
                                String chunk = delta.reasoningContent();
                                sink.tryEmitNext(new MessageEvent(MessageEvent.Type.REASONING, chunk, null, null, true, seq.getAndIncrement()));
                                reasoningText.append(chunk);
                            }
                            if (delta.content() != null) {
                                String chunk = delta.content();
                                if (StrUtil.isEmpty(contentText)) {
                                    sink.tryEmitNext(new MessageEvent(MessageEvent.Type.CONTENT, "\n", null, null, true, seq.getAndIncrement()));
                                }
                                sink.tryEmitNext(new MessageEvent(MessageEvent.Type.CONTENT, chunk, null, null, true, seq.getAndIncrement()));
                                contentText.append(chunk);
                            }
                            if (delta.toolCalls() != null) {
                                for (ToolCall toolCall : delta.toolCalls()) {
//                                    log.info(toolCall.toString());
                                    Integer index = toolCall.index();
                                    if (toolCallMap.containsKey(index)) {
                                        ToolCallContext toolCallContext = toolCallMap.get(index);
                                        toolCallContext.setRawParameters(toolCallContext.getRawParameters() + toolCall.function().arguments());
                                    } else {
                                        toolCallMap.put(
                                                index,
                                                ToolCallContext.builder()
                                                        .callId(toolCall.id())
                                                        .toolName(toolCall.function().name())
                                                        .rawParameters(toolCall.function().arguments())
                                                        .build()
                                        );
                                    }

                                    // NOTE: Removed per-chunk TOOL_CALL emission here so the UI receives a single concatenated
                                    // TOOL_CALL event later (one line per tool call). Partial chunks are accumulated in
                                    // toolCallMap and the final TOOL_CALL snapshot is emitted after the model finishes this round.
                                }
                            }

                            if (choice.finishReason() != null) {
                                log.info("Chat completed with reason: {}", choice.finishReason());
                                finishReason.append(choice.finishReason());
                                countDownLatch.countDown();
                            }


                        },
                        error -> {
                            log.error("Error from deepSeekClient chat stream", error);
                            // emit error to UI
                            sink.tryEmitNext(new MessageEvent(MessageEvent.Type.ERROR, "Stream error: " + error.getMessage(), null, null, false, seq.getAndIncrement()));
                            // ensure the outer loop will stop after this round
                            finishReason.append("stop");
                            countDownLatch.countDown();
                        }
                );

                // wait for this round to finish
                try {
                    boolean awaited = countDownLatch.await(5, TimeUnit.MINUTES);
                    if (!awaited) {
                        sink.tryEmitNext(new MessageEvent(MessageEvent.Type.ERROR, "Timed out waiting for chat response", null, null, false, seq.getAndIncrement()));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    sink.tryEmitNext(new MessageEvent(MessageEvent.Type.ERROR, "Interrupted while waiting for chat response", null, null, false, seq.getAndIncrement()));
                    break;
                }

                if ("stop".contentEquals(finishReason)) {
                    // final answer
//                    sink.tryEmitNext(new MessageEvent(MessageEvent.Type.FINAL, contentText.toString(), null, null, false, seq.getAndIncrement()));
                    sink.tryEmitComplete();
                    return;
                }

                // Otherwise we need to execute tool calls (if any)
                List<ToolCall> toolCalls = new ArrayList<>();
                List<ToolMessage> toolMessages = new ArrayList<>();

                for (ToolCallContext value : toolCallMap.values()) {
                    // emit TOOL_CALL event (final snapshot) using formatted single-line display
                    String display = formatToolCallDisplay(value);
                    sink.tryEmitNext(new MessageEvent(MessageEvent.Type.TOOL_CALL, display, value.getToolName(), value.getCallId(), false, seq.getAndIncrement()));

                    try {
                        toolRegistry.executeTool(value); // may block; we're on boundedElastic
                        sink.tryEmitNext(new MessageEvent(MessageEvent.Type.TOOL_RESULT, value.getResult(), value.getToolName(), value.getCallId(), false, seq.getAndIncrement()));
                    } catch (Exception ex) {
                        log.error("Tool execution failed: {}", value.getToolName(), ex);
                        sink.tryEmitNext(new MessageEvent(MessageEvent.Type.ERROR, "Tool execution failed: " + ex.getMessage(), value.getToolName(), value.getCallId(), false, seq.getAndIncrement()));
                        // still attach whatever result we have
                    }

                    ToolCall toolCall = ToolCall.builder()
                            .id(value.getCallId())
                            .index(toolCalls.size())
                            .type(ToolType.FUNCTION)
                            .function(
                                    FunctionCall.builder()
                                            .name(value.getToolName())
                                            .arguments(value.getRawParameters())
                                            .build()
                            )
                            .build();
                    toolCalls.add(toolCall);

                    ToolMessage toolMessage = ToolMessage.builder()
                            .toolCallId(value.getCallId())
                            .content(Objects.toString(value.getResult(), ""))
                            .build();
                    toolMessages.add(toolMessage);
                }

                AssistantMessage assistantMessage = AssistantMessage.builder()
                        .reasoningContent(reasoningText.toString())
                        .content(contentText.toString())
                        .toolCalls(toolCalls)
                        .build();

                // append assistant message and tool messages for next round
                request.messages().add(assistantMessage);
                request.messages().addAll(toolMessages);

                // loop to process the next model response
            }
        } catch (Exception e) {
            log.error("Orchestration error", e);
            sink.tryEmitNext(new MessageEvent(MessageEvent.Type.ERROR, "Orchestration error: " + e.getMessage(), null, null, false, seq.getAndIncrement()));
            sink.tryEmitComplete();
        }
    }

    // Helper that compacts whitespace/newlines and builds a single-line display for a tool call
    private String formatToolCallDisplay(ToolCallContext value) {
        if (value == null) return "";
        String params = value.getRawParameters();
        if (params == null) params = "";
        // collapse whitespace/newlines into single spaces, trim leading/trailing spaces
        String compact = params.replaceAll("\\s+", " ").trim();
        // if the params string already looks like a JSON object, keep it as-is after compaction
        if (!compact.isEmpty()) {
            return value.getToolName() + ": " + compact;
        } else {
            return value.getToolName() + ": {}";
        }
    }

    public void recursiveToolCall(ChatCompletionRequest request) {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        StringBuilder reasoningText = new StringBuilder();
        StringBuilder contentText = new StringBuilder();
        Map<Integer, ToolCallContext> toolCallMap = new LinkedHashMap<>();
        StringBuilder finishReason = new StringBuilder();

        deepSeekClient.chatFluxCompletion(request).subscribe(
                response -> {
                    ChatCompletionChoice choice = response.choices().get(0);
                    Delta delta = response.choices().get(0).delta();

                    if (delta.reasoningContent() != null) {
                        System.out.println("Received reasoning content chunk: " + delta.reasoningContent());
                        reasoningText.append(delta.reasoningContent());
                    }
                    if (delta.content() != null) {
                        System.out.println("Received content chunk: " + delta.content());
                        contentText.append(delta.content());
                    }
                    if (delta.toolCalls() != null) {
                        for (ToolCall toolCall : delta.toolCalls()) {
                            log.info(toolCall.toString());
                            Integer index = toolCall.index();
                            if (toolCallMap.containsKey(index)) {
                                ToolCallContext toolCallContext = toolCallMap.get(index);
                                toolCallContext.setRawParameters(toolCallContext.getRawParameters() + toolCall.function().arguments());
                            } else {
                                toolCallMap.put(
                                        index,
                                        ToolCallContext.builder()
                                                .callId(toolCall.id())
                                                .toolName(toolCall.function().name())
                                                .rawParameters(toolCall.function().arguments())
                                                .build()
                                );
                            }
                        }
                    }

                    if (choice.finishReason() != null) {
                        log.info("Chat completed with reason: {}", choice.finishReason());
                        finishReason.append(choice.finishReason());
                        countDownLatch.countDown();
                    }


                },
                error -> {
                    log.error("Error from deepSeekClient chat stream in recursiveToolCall", error);
                    // mark stop so we don't loop infinitely
                    finishReason.append("stop");
                    countDownLatch.countDown();
                }
        );

        try {
            boolean awaited = countDownLatch.await(5, TimeUnit.MINUTES);
            if (!awaited) {
                log.error("Timed out waiting for chat response in recursiveToolCall");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting in recursiveToolCall", e);
        }

        if ("stop".contentEquals(finishReason)) {
            System.out.println("Chat completed successfully.");
            return;
        }


        List<ToolCall> toolCalls = new ArrayList<>();

        for (ToolCallContext value : toolCallMap.values()) {
            toolRegistry.executeTool(value);
            System.out.println(value);
            ToolCall toolCall = ToolCall.builder()
                    .id(value.getCallId())
                    .index(toolCalls.size())
                    .type(ToolType.FUNCTION)
                    .function(
                            FunctionCall.builder()
                                    .name(value.getToolName())
                                    .arguments(value.getRawParameters())
                                    .build()
                    )
                    .build();
            toolCalls.add(toolCall);
        }

        AssistantMessage assistantMessage = AssistantMessage.builder()
                .reasoningContent(reasoningText.toString())
                .content(contentText.toString())
                .toolCalls(toolCalls)
                .build();

        List<ToolMessage> toolMessages = new ArrayList<>();
        for (ToolCallContext value : toolCallMap.values()) {
            ToolMessage toolMessage = ToolMessage.builder()
                    .toolCallId(value.getCallId())
                    .content(Objects.toString(value.getResult(), ""))
                    .build();
            toolMessages.add(toolMessage);
        }

        request.messages().add(assistantMessage);
        request.messages().addAll(toolMessages);

        recursiveToolCall(request);
    }

    //string, number, integer, boolean, array, object
    private JsonSchemaElement paramMetadataToJsonSchemaElement(ParamMetadata param) {
        if (param.getEnumValues() != null && !param.getEnumValues().isEmpty()) {
            return JsonEnumSchema.builder()
                    .description(param.getDescription())
                    .enumValues(param.getEnumValues())
                    .build();
        }

        switch (param.getType()) {
            case "string":
                return JsonStringSchema.builder()
                        .description(param.getDescription())
                        .build();
            case "number":
                return JsonNumberSchema.builder()
                        .description(param.getDescription())
                        .build();
            case "integer":
                return JsonIntegerSchema.builder()
                        .description(param.getDescription())
                        .build();
            case "boolean":
                return JsonBooleanSchema.builder()
                        .description(param.getDescription())
                        .build();
            case "array":
                return JsonArraySchema.builder()
                        .description(param.getDescription())
                        .build();
            case "object":
                return JsonObjectSchema.builder()
                        .description(param.getDescription())
                        .build();
            default:
                throw new IllegalArgumentException("Unsupported parameter type: " + param.getType());
        }
    }

    /**
     * 将本地工具转换为DeepSeek工具格式
     */
    private List<Tool> convertToTools(List<ToolMetadata> localTools) {

        List<Tool> tools = new ArrayList<>();


        for (ToolMetadata metadata : localTools) {
            if (!metadata.isEnabled()) {
                continue;
            }

            Map<String, JsonSchemaElement> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            if (metadata.getParameters() != null) {
                for (ParamMetadata param : metadata.getParameters()) {

                    properties.put(param.getName(), paramMetadataToJsonSchemaElement(param));

                    if (param.isRequired()) {
                        required.add(param.getName());
                    }
                }
            }

            // 构建函数
            Function function = Function.builder()
                    .name(metadata.getName())
                    .description(metadata.getDescription())
                    .parameters(
                            JsonObjectSchema.builder()
                                    .properties(properties)
                                    .required(required)
                                    .build())
                    .build();

            // 构建工具
            Tool tool = Tool.from(function);
            tools.add(tool);
        }

        return CollUtil.isEmpty(tools) ? null : tools;
    }

    // lightweight event model used by streamToolCall
    public static class MessageEvent {
        public enum Type { REASONING, CONTENT, TOOL_CALL, TOOL_RESULT, FINAL, ERROR }

        public final Type type;
        public final String text;
        public final String toolName;
        public final String toolCallId;
        public final boolean isPartial;
        public final long seq;

        public MessageEvent(Type type, String text, String toolName, String toolCallId, boolean isPartial, long seq) {
            this.type = type;
            this.text = text;
            this.toolName = toolName;
            this.toolCallId = toolCallId;
            this.isPartial = isPartial;
            this.seq = seq;
        }

        @Override
        public String toString() {
            return "MessageEvent{" +
                    "type=" + type +
                    ", text='" + text + '\'' +
                    ", toolName='" + toolName + '\'' +
                    ", toolCallId='" + toolCallId + '\'' +
                    ", isPartial=" + isPartial +
                    ", seq=" + seq +
                    '}';
        }
    }
}
