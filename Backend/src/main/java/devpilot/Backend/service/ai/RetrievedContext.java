package devpilot.Backend.service.ai;

import java.util.List;

import devpilot.Backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}
