package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import static com.ibm.advocacy.agentic.AgentSupport.pipeline;

public class AttendanceAgent {
    @Agent(outputKey="task",description="Calculate deterministic in-person attendance hours")
    public static PhaseTask execute(@V("task") PhaseTask task) throws Exception { if(task.phase()==2)pipeline().attendance(task);return task; }
}
