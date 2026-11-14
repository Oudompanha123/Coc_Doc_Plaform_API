package com.coc.docs.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * The servlet container's multipart limits (ADR-118).
 *
 * <p>Spring Boot's default is 1 MB per file, which an AX Renewal project's
 * source archive passes at once. This raises the container's limit to that
 * feature's cap; it is the outer bound only. Each upload still enforces its
 * own size — 2 MB for an avatar or a content image, 200 MB for a source
 * archive — so nothing that was refused before is accepted now.
 *
 * <p>A bean rather than {@code spring.servlet.multipart.*}, so the limit
 * sits next to the reason for it.
 *
 * <p>// req: ADR-118
 */
@Configuration
public class MultipartUploadConfig {

    /** Matches {@code AxRenewalSourceStorage.MAX_BYTES}, which reports the friendlier error just below this. */
    private static final DataSize MAX_FILE = DataSize.ofMegabytes(200);

    @Bean
    MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(MAX_FILE);
        // A little room for the part's own headers and boundary.
        factory.setMaxRequestSize(DataSize.ofBytes(MAX_FILE.toBytes() + DataSize.ofMegabytes(1).toBytes()));
        return factory.createMultipartConfig();
    }
}
