package com.galaxy.auratrader.llm.chat;

import io.github.pigmesh.ai.deepseek.core.DeepSeekClient;
import io.github.pigmesh.ai.deepseek.core.SyncOrAsyncOrStreaming;
import io.github.pigmesh.ai.deepseek.core.chat.ChatCompletionResponse;
import io.github.pigmesh.ai.deepseek.core.chat.Delta;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class Chatter {
    private final DeepSeekClient deepSeekClient;


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
}
