package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class AttributionAgent {
    @Agent(outputKey="task",description="Split author by tool combinations and conserve totals")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { if(task.phase()==3)pipeline().attribution(task);return task; }
}
