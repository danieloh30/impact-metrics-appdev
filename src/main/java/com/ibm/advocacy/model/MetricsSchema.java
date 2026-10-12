package com.ibm.advocacy.model;

import java.util.*;

/** Shared CSV headers, activity classifications, and phase names. */
public final class MetricsSchema {
    private MetricsSchema() {}
    public static final String URL="Link / URL to resource", VIEWS="Views / attendance", NOTE="Scrape notes";
    public static final List<String> OUTPUTS=List.of("digital.csv","in-person.csv","follow-up.csv","updated_90-day.csv","current.csv");
    public static final List<String> FINAL_HEADERS=List.of("Date","Author","Tool","Strategic initiative","Name of activity","Event","Collection","Type of activity","Trailing 90-day","Geography","Current total","Previous total",VIEWS,"Weight","Impact hours",URL,"Notes / description","Column1","Column2",NOTE,"total_reactions","total_comments","total_reposts","reactions_scraped_at");
    public static final Set<String> DIGITAL=Set.of("3rd party conference video","blog post / slide shares / .io updates","digital conference / event","livestream","podcast or webinar (internal / external)","recorded video","demos (archive / github)","development - tools, add-ons (registry)","development - tools (github)","forum answer (ibm or practitioner community)","twitter / facebook / linkedin / bluesky / weibo");
    public static final Set<String> PERSON=Set.of("booth duty","conference keynote or breakout (proprietary or 3rd party)","conference workshop (proprietary or 3rd party)","csp or other partner presentation (digital or in-person)","customer meeting","customer workshop","customer presentation","internal team member enablement","meetup (user group)","standard participation");
    public static final Set<String> OTHER=Set.of("analyst or press briefing","external conference cfp panel","whitepaper");
    public static final List<String> PHASES=List.of("Digital collection","In-person attendance","Author / tool attribution","Trailing 90 days","Other activities","Combine exports","Current totals");
}
