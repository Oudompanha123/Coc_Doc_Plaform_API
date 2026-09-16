package com.coc.docs.storage;

import org.springframework.web.multipart.MultipartFile;

/**
 * Stores an image an admin uploads into a docs-content field (a feature
 * card's icon, so far) and returns its public URL — the URL is what
 * actually gets saved in {@code docs_content.body}, same as any other
 * field value.
 *
 * <p>Optional, same shape as {@link com.coc.docs.account.AvatarStorage}: a
 * deployment with no object-storage credentials configured registers no
 * bean at all (see {@link ContentImageStorageConfig}), and callers resolve
 * it through an {@code ObjectProvider} rather than a required dependency.
 */
public interface ContentImageStorage {

    String upload(MultipartFile file);
}
