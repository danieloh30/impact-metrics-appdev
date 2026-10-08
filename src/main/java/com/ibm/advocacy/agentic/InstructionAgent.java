package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class InstructionAgent {
    @Agent(outputKey="task",description="Validate frozen RUN.md and AGENTS.md instructions inside runtimes")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { pipeline().instructions(task);return task; }
}
