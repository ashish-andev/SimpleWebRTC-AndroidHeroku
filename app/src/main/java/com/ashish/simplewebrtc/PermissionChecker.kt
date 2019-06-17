package com.ashish.simplewebrtc

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat

import java.util.ArrayList

class PermissionChecker {

    val REQUEST_MULTIPLE_PERMISSION = 100
    var callbackMultiple: VerifyPermissionsCallback? = null

    fun verifyPermissions(activity: Activity, permissions: Array<String>, callback: VerifyPermissionsCallback?) {
        val denyPermissions = getDenyPermissions(activity, permissions)
        if (denyPermissions.size > 0) {
            ActivityCompat.requestPermissions(activity, denyPermissions, REQUEST_MULTIPLE_PERMISSION)
            this.callbackMultiple = callback
        } else {
            callback?.onPermissionAllGranted()
        }
    }

    fun getDenyPermissions(context: Context, permissions: Array<String>): Array<String> {
        val denyPermissions = ArrayList<String>()
        for (permission in permissions) {
            if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                denyPermissions.add(permission)
            }
        }
        return denyPermissions.toTypedArray()
    }

    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_MULTIPLE_PERMISSION -> if (grantResults.size > 0 && callbackMultiple != null) {
                val denyPermissions = ArrayList<String>()
                var i = 0
                for (permission in permissions) {
                    if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                        denyPermissions.add(permission)
                    }
                    i++
                }
                if (denyPermissions.size == 0) {
                    callbackMultiple!!.onPermissionAllGranted()
                } else {
                    callbackMultiple!!.onPermissionDeny(denyPermissions.toTypedArray())
                }
            }
        }
    }

    interface VerifyPermissionsCallback {
        fun onPermissionAllGranted()

        fun onPermissionDeny(permissions: Array<String>)
    }

    companion object {

        fun hasPermissions(context: Context, permissions: Array<String>): Boolean {
            for (permission in permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }
            return true
        }
    }
}
