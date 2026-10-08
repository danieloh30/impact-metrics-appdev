package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PreparedWorkbook;
import com.ibm.advocacy.model.WorkbookRequest;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

public interface WorkbookWorkflow {
    @SequenceAgent(outputKey="prepared", subAgents={WorkbookPreparationAgent.class})
    PreparedWorkbook execute(@MemoryId String memoryId, @V("request") WorkbookRequest request);
}
