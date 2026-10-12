package com.ibm.advocacy.collector;

import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DZoneWindowTest {
    @Test void waitsForAsynchronousMinimizationBeforeReturning() {
        long[] clock={0};int[] requests={0};
        DZoneCollector.awaitMinimized(()->requests[0]++,()->clock[0]>=150_000_000L,
            ()->clock[0]+=50_000_000L,()->clock[0]);
        assertEquals(350_000_000L,clock[0]);assertTrue(requests[0]>1);
    }
    @Test void startupRestoreRestartsTheStableInterval() {
        long[] clock={0};int[] requests={0};
        DZoneCollector.awaitMinimized(()->requests[0]++,()->clock[0]!=150_000_000L,
            ()->clock[0]+=50_000_000L,()->clock[0]);
        assertEquals(400_000_000L,clock[0]);assertEquals(2,requests[0]);
    }
    @Test void windowThatCannotBeMinimizedFailsWithinTheDeadline() {
        long[] clock={0};
        var failure=assertThrows(PlaywrightException.class,()->DZoneCollector.awaitMinimized(()->{},()->false,
            ()->clock[0]+=50_000_000L,()->clock[0]));
        assertEquals(5_000_000_000L,clock[0]);assertTrue(failure.getMessage().contains("within 5 seconds"));
    }
    @Test void interruptionStopsTheWaitAndPreservesTheFlag() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(PlaywrightException.class,()->DZoneCollector.awaitMinimized(()->{},()->false,()->fail("Must not poll"),()->0L));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
}
