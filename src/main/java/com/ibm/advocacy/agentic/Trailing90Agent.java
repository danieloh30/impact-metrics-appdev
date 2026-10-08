package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class Trailing90Agent {
    @Agent(outputKey="task",description="Refresh grouped trailing totals and preserve previous baselines")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { if(task.phase()==4)pipeline().trailing(task);return task; }
}
