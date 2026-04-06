    @ReactMethod
    fun checkCallScreeningPermission(promise: Promise) {
        var granted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = reactApplicationContext.getSystemService(android.app.role.RoleManager::class.java)
            granted = roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == true
        }
        promise.resolve(granted)
    }

    @ReactMethod
    fun requestCallScreeningPermission(promise: Promise) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val activity = currentActivity
            if (activity != null) {
                val roleManager = activity.getSystemService(android.app.role.RoleManager::class.java)
                if (roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING) == false) {
                    val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                    activity.startActivityForResult(intent, 1002)
                }
            }
        }
        promise.resolve(true)
    }
