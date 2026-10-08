package com.coc.docs.notify;

/**
 * What a push banner shows, when a sender wants more than its type's fixed
 * title and sentence (ADR-103).
 *
 * <p>Only announcements use it today. Every other type still pushes its fixed
 * wording, because their subject is a person or an account and a lock screen
 * is not the place for it (ADR-025 decision 6). An announcement is the
 * company talking to everyone, which is the reason it was written.
 *
 * @param title the banner's heading
 * @param body the banner's text
 * @param image a site path for a large picture shown with the banner, or null
 * @param tag what the browser collapses on — a new banner with the same tag
 *     silently replaces the old one, without showing; null for the type's key,
 *     which is what every collapsing type wants
 *
 * <p>// req: ADR-103
 */
public record PushBanner(String title, String body, String image, String tag) {}
