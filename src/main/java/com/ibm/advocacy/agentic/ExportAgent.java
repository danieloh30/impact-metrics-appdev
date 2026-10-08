package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class ExportAgent {
    @Agent(outputKey="task",description="Retain other activities and generate final 24-column exports")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { if(task.phase()>=5)pipeline().finish(task);return task; }
}
