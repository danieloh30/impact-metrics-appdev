package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseResult;
import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class ValidationAgent {
    @Agent(outputKey="result",description="Validate and publish a versioned phase for human approval")
    public static PhaseResult execute(@V("task") PhaseTask task) throws Exception { return pipeline().publish(task); }
}
