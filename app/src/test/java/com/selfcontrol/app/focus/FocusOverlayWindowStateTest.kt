package com.selfcontrol.app.focus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusOverlayWindowStateTest {
    @Test fun missingViewIsNotShowing() {
        assertFalse(canReuseFocusOverlayWindow(false, false, false, false, false, null))
    }

    @Test fun detachedOldWindowCannotSuppressTheNextShow() {
        assertTrue(canReuseFocusOverlayWindow(true, true, true, true, true, null))
        assertFalse(canReuseFocusOverlayWindow(true, false, false, false, false, null))
        // A lingering parent alone must not keep an old window alive.
        assertFalse(canReuseFocusOverlayWindow(true, false, false, true, false, null))
    }

    @Test fun attachedWindowWithoutTokenIsNotReusable() {
        assertFalse(canReuseFocusOverlayWindow(true, true, false, true, true, null))
    }

    @Test fun windowWithoutParentIsNotReusable() {
        assertFalse(canReuseFocusOverlayWindow(true, true, true, false, true, null))
    }

    @Test fun validWindowRemainsReusableAcrossRepeatedChecks() {
        repeat(5) {
            assertTrue(canReuseFocusOverlayWindow(true, true, true, true, true, null))
        }
    }

    @Test fun firstAttachmentDoesNotCauseRemoveAddChurn() {
        // addView has registered the parent, but the first traversal has not run.
        assertTrue(canReuseFocusOverlayWindow(true, false, false, true, false, 0L))
        // The attach callback ends the exception; the attached window stays reusable.
        assertTrue(canReuseFocusOverlayWindow(true, true, true, true, true, null))
        // A later detach must not benefit from the first-attachment exception.
        assertFalse(canReuseFocusOverlayWindow(true, false, false, true, false, null))
    }

    @Test fun pendingAttachmentWithoutRegistrationIsNotReusable() {
        assertFalse(canReuseFocusOverlayWindow(true, false, false, false, false, 0L))
    }

    @Test fun stalledFirstAttachmentCannotStayShowingForever() {
        assertTrue(canReuseFocusOverlayWindow(true, false, false, true, false,
            FOCUS_OVERLAY_ATTACH_GRACE_MS - 1L))
        // A parent left behind without an attach callback must no longer suppress recreation.
        for (age in listOf(FOCUS_OVERLAY_ATTACH_GRACE_MS, 60_000L, -1L)) {
            assertFalse(canReuseFocusOverlayWindow(true, false, false, true, false, age))
        }
    }

    @Test fun invisibleAttachedWindowCannotSuppressRecreation() {
        assertFalse(canReuseFocusOverlayWindow(true, true, true, true, false, null))
        // A pending marker cannot bypass visibility or token checks after attachment.
        assertFalse(canReuseFocusOverlayWindow(true, true, true, true, false, 0L))
        assertFalse(canReuseFocusOverlayWindow(true, true, false, true, true, 0L))
    }

    @Test fun successfulAttachmentRemainsReusableAfterGracePeriod() {
        assertTrue(canReuseFocusOverlayWindow(true, true, true, true, true, 60_000L))
    }

    @Test fun clearedReferenceCannotBeResurrectedByStaleWindowFlags() {
        assertFalse(canReuseFocusOverlayWindow(false, true, true, true, true, 0L))
    }
}
