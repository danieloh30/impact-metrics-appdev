package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.*;
import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.*;

public interface PublicationWorkflow {
    @SequenceAgent(outputKey="publication",subAgents={MonthlyPublicationAgent.class})
    WorkbookPublication execute(@MemoryId String memoryId,@V("publicationTask") PublicationTask task);
}
