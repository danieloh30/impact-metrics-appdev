package com.ibm.advocacy.agentic;

import com.ibm.advocacy.model.PreparedWorkbook;
import com.ibm.advocacy.model.WorkbookRequest;
import com.ibm.advocacy.service.WorkbookService;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import jakarta.enterprise.inject.spi.CDI;

public class WorkbookPreparationAgent {
    @Agent(outputKey="prepared", description="Prepare structured reporting-month and trailing history data with downloadable snapshots from selected Excel sheets without inventing historical totals")
    public static PreparedWorkbook execute(@V("request") WorkbookRequest request) throws Exception {
        return CDI.current().select(WorkbookService.class).get().prepare(request);
    }
}
