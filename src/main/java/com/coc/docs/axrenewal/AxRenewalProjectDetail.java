package com.coc.docs.axrenewal;

import java.util.List;
import java.util.Map;

/**
 * Everything one project's screen renders, assembled by {@link
 * AxRenewalService#detail} (ADR-037).
 *
 * @param analysis null until {@code runAnalysis} has been called at least once.
 * @param architecture null until the Architecture section has ever been saved.
 * @param dbConnection null until a database has been connected — metadata
 *     only, never a live connection (see the table's own comment, ADR-037).
 * @param destination null until an output destination has been set.
 * @param confirmations one entry per confirmed section; a section absent from
 *     this map is unconfirmed.
 * @param latestImplementationRun null until an implementation has been started at least once.
 */
public record AxRenewalProjectDetail(
        AxRenewalProject project,
        AxRenewalAnalysis analysis,
        List<AxRenewalStackLayer> stackLayers,
        AxRenewalArchitecturePlan architecture,
        List<AxRenewalPageMapping> pageMappings,
        List<AxRenewalDbChange> dbChanges,
        AxRenewalDbConnection dbConnection,
        AxRenewalDestination destination,
        Map<AxRenewalSection, AxRenewalConfirmation> confirmations,
        AxRenewalImplementationRun latestImplementationRun,
        List<AxRenewalComment> comments) {}
