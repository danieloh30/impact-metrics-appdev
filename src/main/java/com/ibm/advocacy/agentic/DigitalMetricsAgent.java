package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class DigitalMetricsAgent {
    @Agent(outputKey="task",description="Collect evidence-backed current-month digital metrics")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { if(task.phase()==1)pipeline().digital(task);return task; }
}
