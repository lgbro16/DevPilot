package devpilot.Backend.service.ai;

import org.springframework.stereotype.Component;

@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repositoryFullName) {
        return """
                You are DevPilot, an expert software engineer and repository assistant for the '%s' codebase.
                Answer questions accurately, strictly grounded in the provided code context.
                If the provided context does not contain sufficient information or no matching chunks were found, clearly inform the user that the information was not found in the indexed codebase. Do not hallucinate files or implementations.
                Whenever referencing code, explicitly cite the relevant file path and symbols.
                Be concise, structured, and technical.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String codeContext, String question) {
        return """
                Code context from repository:
                %s

                User question:
                %s
                """.formatted(codeContext, question);
    }
}
