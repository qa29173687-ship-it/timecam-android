package tw.timecam.upload

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Google 授權（Drive `drive.file` 範圍）。
 * 需要在 Google Cloud 為 package `tw.timecam` + 釋出憑證 SHA-1 建立 Android OAuth 用戶端，
 * 並啟用 Google Drive API。尚未設定時會回傳「需要設定 Google 登入」。
 */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val SETUP_MSG = "需要設定 Google 登入"
    const val RELINK_MSG = "請到設定重新連結 Google 帳號"

    sealed class Outcome {
        data class Token(val accessToken: String, val email: String?) : Outcome()
        data class NeedsUi(val pendingIntent: PendingIntent) : Outcome()
        data class Fail(val message: String, val needsSetup: Boolean = false) : Outcome()
    }

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /** 從設定頁發起（必要時跳出帳號／同意畫面） */
    suspend fun authorize(activity: android.app.Activity): Outcome = try {
        interpret(Identity.getAuthorizationClient(activity).authorize(request()).await())
    } catch (e: Exception) { fail(e) }

    /** 從 Worker 等無畫面環境取 token；若需要互動則失敗 */
    suspend fun silentToken(context: Context): Outcome = try {
        val r = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (r.hasResolution()) Outcome.Fail(RELINK_MSG) else interpret(r)
    } catch (e: Exception) { fail(e) }

    fun resultFromIntent(context: Context, data: Intent?): Outcome = try {
        interpret(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    } catch (e: Exception) { fail(e) }

    fun signOut(prefs: UploadPrefs) { prefs.clearAccount() }

    private fun interpret(r: AuthorizationResult): Outcome {
        if (r.hasResolution()) {
            val pi = r.pendingIntent ?: return Outcome.Fail(SETUP_MSG, needsSetup = true)
            return Outcome.NeedsUi(pi)
        }
        val token = r.accessToken
        if (token.isNullOrBlank()) return Outcome.Fail(SETUP_MSG, needsSetup = true)
        return Outcome.Token(token, r.toGoogleSignInAccount()?.email)
    }

    private fun fail(e: Exception): Outcome {
        val api = e as? ApiException ?: e.cause as? ApiException
        val code = api?.statusCode
        val text = (e.message ?: "") + " " + (e.cause?.message ?: "")
        val setup = code == CommonStatusCodes.DEVELOPER_ERROR || code == 10 ||
            text.contains("DEVELOPER_ERROR", true) || text.contains("12500")
        return Outcome.Fail(if (setup) SETUP_MSG else (e.localizedMessage ?: e.message ?: "無法連結 Google 帳號"), needsSetup = setup)
    }
}
