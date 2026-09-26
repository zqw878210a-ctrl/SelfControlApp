package com.selfcontrol.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionGuideTest {
    @Test fun guideOnlyRequestsMissingPermissionsAndUpdatesAfterSettingsReturn() {
        assertEquals(listOf(AppSetupPermission.USAGE_ACCESS, AppSetupPermission.OVERLAY),
            missingAppPermissions(false, false))
        assertEquals(listOf(AppSetupPermission.OVERLAY), missingAppPermissions(true, false))
        assertEquals(emptyList<AppSetupPermission>(), missingAppPermissions(true, true))
        // Also handle permission revocation on a later entry.
        assertEquals(listOf(AppSetupPermission.USAGE_ACCESS), missingAppPermissions(false, true))
    }
}
