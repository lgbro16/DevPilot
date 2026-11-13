package devpilot.Backend.service.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devpilot.Backend.dto.ChatMessageResponse;
import devpilot.Backend.dto.CitationDto;
import devpilot.Backend.entity.ChatMessage;
import devpilot.Backend.entity.MessageRole;
import devpilot.Backend.repository.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class ChatStreamHandler {

    private final ChatModel chatModel;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final GeminiRateLimiter geminiRateLimiter;

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            // Acquire rate limiter permit before initiating chat stream
            geminiRateLimiter.acquire();

            ChatClient.builder(chatModel)
                    .build()
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .doOnNext(token -> appendToken(emitter, fullReply, token))
                    .doOnError(err -> handleStreamError(emitter, sessionId, err, fullReply, citations))
                    .doOnComplete(() -> completeStream(emitter, sessionId, fullReply, citations))
                    .subscribe();
        } catch (Exception ex) {
            handleStreamError(emitter, sessionId, ex, fullReply, citations);
        }

        return emitter;
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(token, MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            log.warn("Could not send SSE token to client: {}", ex.getMessage());
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            String content = fullReply.toString();
            if (content.isBlank()) {
                content = "I could not find sufficient information in the repository to answer your question.";
            }

            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(content)
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            log.error("Error finalizing chat stream: {}", ex.getMessage(), ex);
            emitter.completeWithError(ex);
        }
    }

    private void handleStreamError(
            SseEmitter emitter,
            UUID sessionId,
            Throwable err,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        log.error("Chat streaming encountered error: {}", err.getMessage());

        String userMessage = "The AI service is temporarily unavailable. Please try again in a moment.";
        if (geminiRateLimiter.isRateLimit(err)) {
            geminiRateLimiter.recordRateLimit(15_000L);
            userMessage = "Rate limit reached for the Gemini API. Please wait a few seconds and try your question again.";
        }

        try {
            // If tokens were already sent, record the partial response or error
            if (fullReply.length() > 0) {
                fullReply.append("\n\n*[").append(userMessage).append("]*");
            } else {
                fullReply.append(userMessage);
            }

            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception sendEx) {
            emitter.completeWithError(err);
        }
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}
