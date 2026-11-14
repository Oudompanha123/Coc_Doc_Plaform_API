package com.coc.docs.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code file.*} configuration block: where uploaded files are kept on
 * this backend's own disk (ADR-118).
 *
 * <p>Unvalidated, like {@code AxRenewalProperties}: a deployment that sets
 * nothing still starts, and the upload route answers that it is not available
 * there rather than the application refusing to boot.
 *
 * @param serverPath the folder uploads are written to, e.g. {@code F:\Image}.
 *     Created on first upload if it does not exist yet.
 *
 * <p>// req: ADR-118
 */
@ConfigurationProperties(prefix = "file")
public record FileInfoProperties(String serverPath) {

    public boolean configured() {
        return serverPath != null && !serverPath.isBlank();
    }
}
