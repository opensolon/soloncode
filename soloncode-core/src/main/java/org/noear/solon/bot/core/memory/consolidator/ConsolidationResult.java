package org.noear.solon.bot.core.memory.consolidator;

import java.util.Map;
import java.util.Set;

public class ConsolidationResult {
    public final int originalCount;
    public final int consolidatedCount;
    public final int removedCount;
    public final Map<String, Set<String>> mergeGroups;

    ConsolidationResult(int originalCount, int consolidatedCount,
                        Map<String, Set<String>> mergeGroups) {
        this.originalCount = originalCount;
        this.consolidatedCount = consolidatedCount;
        this.removedCount = originalCount - consolidatedCount;
        this.mergeGroups = mergeGroups;
    }

    public double getReductionRate() {
        return (double) removedCount / originalCount;
    }
}