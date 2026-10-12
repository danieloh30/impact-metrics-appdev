package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.*;
import com.ibm.advocacy.service.WorkbookPublicationService;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import io.quarkus.arc.Arc;

/** Only a reviewed digest is sent to this agent; cookies and workbook bytes stay in Java services. */
public class MonthlyPublicationAgent {
    @Agent(outputKey="publication",description="Publish the human-reviewed monthly workbook upsert and verify the saved result")
    public static WorkbookPublication publish(@V("publicationTask") PublicationTask task) throws Exception {
        return Arc.container().instance(WorkbookPublicationService.class).get().commit(task);
    }
}
