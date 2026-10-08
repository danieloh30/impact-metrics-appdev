package com.ibm.advocacy.agentic;

import com.ibm.advocacy.service.Pipeline;

import com.ibm.advocacy.service.Pipeline;
import jakarta.enterprise.inject.spi.CDI;

/** Resolves the CDI pipeline for the declarative static phase agents. */
final class AgentSupport {
    private AgentSupport() {}
    static Pipeline pipeline() { return CDI.current().select(Pipeline.class).get(); }
}
