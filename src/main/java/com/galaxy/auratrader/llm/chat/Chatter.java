package com.galaxy.auratrader.llm.chat;

import cn.hutool.core.collection.CollUtil;
import com.galaxy.auratrader.llm.tool.*;
import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.SyncOrAsyncOrStreaming;
import io.github.pigmesh.ai.deepseek.core.chat.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class Chatter {
    private final DeepSeekClient deepSeekClient;
    private final AIToolRegistry toolRegistry;


    public void chat(){

        Flux<ChatCompletionResponse> responseFlux = deepSeekClient.chatFluxCompletion("Hello World");
        responseFlux.subscribe(response -> {
//            System.out.println("Received chunk: " + response.choices().get(0).delta().reasoningContent());
//            System.out.println("Received chunk: " + response.choices().get(0).delta().content());
            Delta delta = response.choices().get(0).delta();
            if (delta.reasoningContent()!= null) {
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
            e.printStackTrace();
        }

//        SyncOrAsyncOrStreaming<String> helloWorld = deepSeekClient.chatCompletion("Hello World");
//        String execute = helloWorld.execute();
//        System.out.println("Response: " + execute);
    }

    // 新增方法：返回流式的 ChatCompletionResponse，供 UI 订阅和流式渲染
    public Flux<ChatCompletionResponse> streamChatFlux(String prompt) {
        return deepSeekClient.chatFluxCompletion(prompt);
    }


    public void functionTest(){

        List<ToolMetadata> allToolMetadata = toolRegistry.getAllToolMetadata();
        List<Tool> tools = convertToTools(allToolMetadata);

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(ChatCompletionModel.DEEPSEEK_REASONER) // 请注意只支持Chat模型
                .addUserMessage("南京现在多少度？")
                .tools(tools)  // 添加工具函数
                .build();

        List<ToolCall> toolCalls = new ArrayList<>();

        CountDownLatch countDownLatch = new CountDownLatch(1);

        StringBuilder reasoning = new StringBuilder("");
        StringBuilder content = new StringBuilder("");
        StringBuilder toolCall = new StringBuilder("");

        deepSeekClient.chatFluxCompletion(request).subscribe(
                response -> {
                    ChatCompletionChoice choice = response.choices().get(0);
                    Delta delta = response.choices().get(0).delta();

                    if (delta.reasoningContent() != null) {
                        System.out.println("Received reasoning content chunk: " + delta.reasoningContent());
                        reasoning.append(delta.reasoningContent());
                    }
                    if (delta.content() != null) {
                        System.out.println("Received content chunk: " + delta.content());
                        content.append(delta.content());
                    }
                    if (delta.toolCalls() != null) {
                        for (ToolCall toolCall : delta.toolCalls()) {
                            String id = toolCall.id();
                            if(id != null) {
                                log.info("Tool call made: {} with parameters {}", toolCall.function().name(), toolCall.function().arguments());
                                toolCalls.add(toolCall);
                            }
                        }
                    }

                    if (choice.finishReason() != null) {
                        log.info("Chat completed with reason: {}", choice.finishReason());
                        countDownLatch.countDown();
                    }


                }
        );

        try {
            countDownLatch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        log.info("1234567890 Chat session ended.");


//        if (!toolCalls.isEmpty()) {
//            System.out.println("Tool Calls Made:");
//            for (ToolCall call : toolCalls) {
//                ToolCallContext.builder()
//                        .callId(call.id())
//                        .toolName(call.function().name())
//                        .parameters(call.function().arguments())
//            }
//        } else {
//            System.out.println("No tool calls were made.");
//        }





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

            // 构建参数schema
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");

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

        return CollUtil.isEmpty(tools)?null:tools;
    }
}
