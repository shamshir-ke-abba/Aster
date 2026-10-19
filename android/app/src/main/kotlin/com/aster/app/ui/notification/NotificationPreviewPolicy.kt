package com.aster.app.ui.notification

/**
 * Configurable privacy policy for message preview text on lock screen and banners.
 */
enum class NotificationPreviewPolicy {
    /** Full message preview is displayed in notification banner and shade. */
    SHOW_PREVIEW,

    /** Privacy mode: Displays sender or group name, but masks content as 'New message'. */
    HIDE_PREVIEW_NAME_ONLY
}
