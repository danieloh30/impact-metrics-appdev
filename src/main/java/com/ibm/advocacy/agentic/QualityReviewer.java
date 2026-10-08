package com.ibm.advocacy.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface QualityReviewer {
    @SystemMessage("Explain a metrics phase for human review using only the supplied summary. Never manufacture measurements, grant approval, or claim unresolved issues are resolved. Keep the explanation under 100 words.")
    @UserMessage("Summarize this phase: {{summary}}")
    @Agent(outputKey="qualityReview")
    String review(@V("summary") String summary);
}
