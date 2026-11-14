package com.coc.docs.axrenewal;

import com.coc.docs.audit.AuditAction;
import com.coc.docs.audit.AuditRepository;
import com.coc.docs.auth.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading a project's source (ADR-118): the archive goes to disk through
 * {@link AxRenewalSourceStorage}, and its file name goes into {@code
 * ax_renewal_project.source_filename}.
 *
 * <p>Its own class rather than more of {@link AxRenewalService}, which it
 * still asks who may touch a project — one membership rule, not two.
 *
 * <p>// req: ADR-118
 */
@Service
public class AxRenewalSourceService {

    private final AxRenewalService access;
    private final AxRenewalProjectRepository projects;
    private final AxRenewalSourceStorage storage;
    private final AuditRepository audit;
    private final ObjectMapper json;

    public AxRenewalSourceService(
            AxRenewalService access,
            AxRenewalProjectRepository projects,
            AxRenewalSourceStorage storage,
            AuditRepository audit,
            ObjectMapper json) {
        this.access = access;
        this.projects = projects;
        this.storage = storage;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Stores the uploaded archive and records its file name, replacing any
     * earlier upload.
     *
     * <p>The old file is removed only after the new name has committed, and
     * the new file is removed if recording it fails — so the column never
     * names a file that is not there.
     */
    @Transactional
    public AxRenewalProject upload(UUID projectId, InputStream body, CurrentUser actor) throws IOException {
        AxRenewalProject before = access.requireMember(projectId, actor);
        String fileName = storage.store(body);
        try {
            projects.updateSourceFilename(projectId, fileName);
            audit.append(
                    actor.id(),
                    actor.email(),
                    AuditAction.AX_RENEWAL_SOURCE_UPLOAD,
                    AuditAction.TARGET_AX_RENEWAL_PROJECT,
                    projectId,
                    json.createObjectNode().put("filename", fileName).put("replaced", before.sourceFilename()));
        } catch (RuntimeException e) {
            storage.delete(fileName);
            throw e;
        }
        AxRenewalService.afterCommit(() -> storage.delete(before.sourceFilename()));
        return projects.findById(projectId).orElseThrow();
    }

    /** Removes a deleted project's stored archive, if it had one. */
    public void forget(AxRenewalProject deleted) {
        storage.delete(deleted.sourceFilename());
    }
}
