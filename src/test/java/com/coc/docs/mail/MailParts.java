package com.coc.docs.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.util.Arrays;

/** Reading the parts of a built {@code MimeMessage} in tests — through nested multiparts, as a mail client would. */
public final class MailParts {

    private MailParts() {}

    /** The body part with this Content-ID (angle brackets included), or null. */
    public static BodyPart byContentId(Object content, String contentId) throws Exception {
        if (!(content instanceof Multipart multipart)) {
            return null;
        }
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            String[] ids = part.getHeader("Content-ID");
            if (ids != null && Arrays.asList(ids).contains(contentId)) {
                return part;
            }
            BodyPart nested = byContentId(part.getContent(), contentId);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /** The message's HTML body, or null. */
    public static String html(Part part) throws Exception {
        if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        }
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                String found = html(multipart.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The logo part {@link EmailLayout#embedLogo} attaches, or null. */
    public static BodyPart logo(Part message) throws Exception {
        return byContentId(message.getContent(), "<" + EmailLayout.LOGO_CID + ">");
    }
}
