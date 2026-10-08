package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PhaseResult;
import com.ibm.advocacy.model.PhaseTask;

import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.V;

public interface PhaseWorkflow {
    @SequenceAgent(outputKey="result",subAgents={InstructionAgent.class,DigitalMetricsAgent.class,AttendanceAgent.class,AttributionAgent.class,Trailing90Agent.class,ExportAgent.class,ValidationAgent.class})
    PhaseResult execute(@MemoryId String memoryId,@V("task") PhaseTask task);
}
