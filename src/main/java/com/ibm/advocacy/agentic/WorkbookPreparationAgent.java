package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PreparedWorkbook;
import com.ibm.advocacy.model.WorkbookRequest;
import com.ibm.advocacy.service.WorkbookService;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import jakarta.enterprise.inject.spi.CDI;

public class WorkbookPreparationAgent {
    @Agent(outputKey="prepared", description="Extract the reporting month and trailing 90-day inputs from selected Excel sheets without inventing historical totals")
    public static PreparedWorkbook execute(@V("request") WorkbookRequest request) throws Exception {
        return CDI.current().select(WorkbookService.class).get().prepare(request);
    }
}
