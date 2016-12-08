/*
 * 				Twidere - Twitter client for Android
 * 
 *  Copyright (C) 2012-2014 Mariotaku Lee <mariotaku.lee@gmail.com>
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 * 
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 * 
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.activity

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.support.v4.content.ContextCompat
import android.support.v4.view.ViewCompat
import android.support.v7.app.AlertDialog
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.Pair
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.View.OnClickListener
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bluelinelabs.logansquare.LoganSquare
import com.rengwuxian.materialedittext.MaterialEditText
import kotlinx.android.synthetic.main.activity_sign_in.*
import org.mariotaku.ktextension.set
import org.mariotaku.microblog.library.MicroBlog
import org.mariotaku.microblog.library.MicroBlogException
import org.mariotaku.microblog.library.twitter.TwitterOAuth
import org.mariotaku.microblog.library.twitter.auth.BasicAuthorization
import org.mariotaku.microblog.library.twitter.auth.EmptyAuthorization
import org.mariotaku.microblog.library.twitter.model.Paging
import org.mariotaku.microblog.library.twitter.model.User
import org.mariotaku.restfu.http.Endpoint
import org.mariotaku.restfu.oauth.OAuthAuthorization
import org.mariotaku.restfu.oauth.OAuthToken
import org.mariotaku.twidere.BuildConfig
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants.*
import org.mariotaku.twidere.activity.iface.APIEditorActivity
import org.mariotaku.twidere.annotation.AccountType
import org.mariotaku.twidere.annotation.AuthTypeInt
import org.mariotaku.twidere.fragment.BaseDialogFragment
import org.mariotaku.twidere.fragment.ProgressDialogFragment
import org.mariotaku.twidere.model.ParcelableUser
import org.mariotaku.twidere.model.SingleResponse
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.account.StatusNetAccountExtras
import org.mariotaku.twidere.model.account.TwitterAccountExtras
import org.mariotaku.twidere.model.account.cred.BasicCredentials
import org.mariotaku.twidere.model.account.cred.Credentials
import org.mariotaku.twidere.model.account.cred.EmptyCredentials
import org.mariotaku.twidere.model.account.cred.OAuthCredentials
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.model.util.ParcelableUserUtils
import org.mariotaku.twidere.model.util.UserKeyUtils
import org.mariotaku.twidere.provider.TwidereDataStore.Accounts
import org.mariotaku.twidere.util.*
import org.mariotaku.twidere.util.OAuthPasswordAuthenticator.*
import org.mariotaku.twidere.util.view.ConsumerKeySecretValidator
import java.lang.ref.WeakReference
import java.util.*


class SignInActivity : BaseActivity(), OnClickListener, TextWatcher {
    private var apiUrlFormat: String? = null
    @Credentials.Type
    private var authType: String = Credentials.Type.EMPTY
    private var consumerKey: String? = null
    private var consumerSecret: String? = null
    private var apiChangeTimestamp: Long = 0
    private var sameOAuthSigningUrl: Boolean = false
    private var noVersionSuffix: Boolean = false
    private var signInTask: AbstractSignInTask? = null

    private var accountAuthenticatorResponse: AccountAuthenticatorResponse? = null
    private var accountAuthenticatorResult: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountAuthenticatorResponse = intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
        accountAuthenticatorResponse?.onRequestContinued()

        setContentView(R.layout.activity_sign_in)

        if (savedInstanceState != null) {
            apiUrlFormat = savedInstanceState.getString(Accounts.API_URL_FORMAT)
            authType = savedInstanceState.getString(Accounts.AUTH_TYPE)
            sameOAuthSigningUrl = savedInstanceState.getBoolean(Accounts.SAME_OAUTH_SIGNING_URL)
            consumerKey = savedInstanceState.getString(Accounts.CONSUMER_KEY)?.trim()
            consumerSecret = savedInstanceState.getString(Accounts.CONSUMER_SECRET)?.trim()
            apiChangeTimestamp = savedInstanceState.getLong(EXTRA_API_LAST_CHANGE)
        }

        val isTwipOMode = authType == Credentials.Type.EMPTY
        usernamePasswordContainer.visibility = if (isTwipOMode) View.GONE else View.VISIBLE
        signInSignUpContainer.orientation = if (isTwipOMode) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        editUsername.addTextChangedListener(this)
        editPassword.addTextChangedListener(this)

        signIn.setOnClickListener(this)
        signUp.setOnClickListener(this)
        passwordSignIn.setOnClickListener(this)

        val color = ColorStateList.valueOf(ContextCompat.getColor(this,
                R.color.material_light_green))
        ViewCompat.setBackgroundTintList(signIn, color)


        val consumerKey = preferences.getString(KEY_CONSUMER_KEY, null)
        val consumerSecret = preferences.getString(KEY_CONSUMER_SECRET, null)
        if (BuildConfig.SHOW_CUSTOM_TOKEN_DIALOG && savedInstanceState == null &&
                !preferences.getBoolean(KEY_CONSUMER_KEY_SECRET_SET, false) &&
                !Utils.isCustomConsumerKeySecret(consumerKey, consumerSecret)) {
            val df = SetConsumerKeySecretDialogFragment()
            df.isCancelable = false
            df.show(supportFragmentManager, "set_consumer_key_secret")
        }

        updateSignInType()
        setSignInButton()
    }

    override fun onDestroy() {
        loaderManager.destroyLoader(0)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sign_in, menu)
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_EDIT_API -> {
                if (resultCode == Activity.RESULT_OK) {
                    apiUrlFormat = data!!.getStringExtra(Accounts.API_URL_FORMAT)
                    authType = data.getStringExtra(Accounts.AUTH_TYPE) ?: Credentials.Type.OAUTH
                    sameOAuthSigningUrl = data.getBooleanExtra(Accounts.SAME_OAUTH_SIGNING_URL, false)
                    noVersionSuffix = data.getBooleanExtra(Accounts.NO_VERSION_SUFFIX, false)
                    consumerKey = data.getStringExtra(Accounts.CONSUMER_KEY)
                    consumerSecret = data.getStringExtra(Accounts.CONSUMER_SECRET)
                    updateSignInType()
                }
                setSignInButton()
                invalidateOptionsMenu()
            }
            REQUEST_BROWSER_SIGN_IN -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    doBrowserLogin(data)
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun finish() {
        accountAuthenticatorResponse?.let { response ->
            // send the result bundle back if set, otherwise send an error.
            if (accountAuthenticatorResult != null) {
                response.onResult(accountAuthenticatorResult)
            } else {
                response.onError(AccountManager.ERROR_CODE_CANCELED, "canceled")
            }
            accountAuthenticatorResponse = null
        }
        super.finish()
    }

    override fun afterTextChanged(s: Editable) {

    }

    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

    }

    internal fun updateSignInType() {
        when (authType) {
            Credentials.Type.XAUTH, Credentials.Type.BASIC -> {
                usernamePasswordContainer.visibility = View.VISIBLE
                signInSignUpContainer.orientation = LinearLayout.HORIZONTAL
            }
            Credentials.Type.EMPTY -> {
                usernamePasswordContainer.visibility = View.GONE
                signInSignUpContainer.orientation = LinearLayout.VERTICAL
            }
            else -> {
                usernamePasswordContainer.visibility = View.GONE
                signInSignUpContainer.orientation = LinearLayout.VERTICAL
            }
        }
    }

    override fun onClick(v: View) {
        when (v) {
            signUp -> {
                val intent = Intent(Intent.ACTION_VIEW).setData(Uri.parse(TWITTER_SIGNUP_URL))
                startActivity(intent)
            }
            signIn -> {
                if (usernamePasswordContainer.visibility != View.VISIBLE) {
                    editUsername.text = null
                    editPassword.text = null
                }
                doLogin()
            }
            passwordSignIn -> {
                executeAfterFragmentResumed {
                    val fm = supportFragmentManager
                    val df = PasswordSignInDialogFragment()
                    df.show(fm.beginTransaction(), "password_sign_in")
                    Unit
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                val accountKeys = DataStoreUtils.getActivatedAccountKeys(this)
                if (accountKeys.isNotEmpty()) {
                    onBackPressed()
                }
            }
            R.id.settings -> {
                if (signInTask != null && signInTask!!.status == AsyncTask.Status.RUNNING)
                    return false
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            }
            R.id.edit_api -> {
                if (signInTask != null && signInTask!!.status == AsyncTask.Status.RUNNING)
                    return false
                setDefaultAPI()
                val intent = Intent(this, APIEditorActivity::class.java)
                intent.putExtra(Accounts.API_URL_FORMAT, apiUrlFormat)
                intent.putExtra(Accounts.AUTH_TYPE, authType)
                intent.putExtra(Accounts.SAME_OAUTH_SIGNING_URL, sameOAuthSigningUrl)
                intent.putExtra(Accounts.NO_VERSION_SUFFIX, noVersionSuffix)
                intent.putExtra(Accounts.CONSUMER_KEY, consumerKey)
                intent.putExtra(Accounts.CONSUMER_SECRET, consumerSecret)
                startActivityForResult(intent, REQUEST_EDIT_API)
            }
        }
        return super.onOptionsItemSelected(item)
    }

    internal fun openBrowserLogin(): Boolean {
        if (authType != Credentials.Type.OAUTH || signInTask != null && signInTask!!.status == AsyncTask.Status.RUNNING)
            return true
        val intent = Intent(this, BrowserSignInActivity::class.java)
        intent.putExtra(Accounts.CONSUMER_KEY, consumerKey)
        intent.putExtra(Accounts.CONSUMER_SECRET, consumerSecret)
        intent.putExtra(Accounts.API_URL_FORMAT, apiUrlFormat)
        intent.putExtra(Accounts.SAME_OAUTH_SIGNING_URL, sameOAuthSigningUrl)
        startActivityForResult(intent, REQUEST_BROWSER_SIGN_IN)
        return false
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val itemBrowser = menu.findItem(R.id.open_in_browser)
        if (itemBrowser != null) {
            val is_oauth = authType == Credentials.Type.OAUTH
            itemBrowser.isVisible = is_oauth
            itemBrowser.isEnabled = is_oauth
        }
        return true
    }

    public override fun onSaveInstanceState(outState: Bundle) {
        setDefaultAPI()
        outState.putString(Accounts.API_URL_FORMAT, apiUrlFormat)
        outState.putString(Accounts.AUTH_TYPE, authType)
        outState.putBoolean(Accounts.SAME_OAUTH_SIGNING_URL, sameOAuthSigningUrl)
        outState.putBoolean(Accounts.NO_VERSION_SUFFIX, noVersionSuffix)
        outState.putString(Accounts.CONSUMER_KEY, consumerKey)
        outState.putString(Accounts.CONSUMER_SECRET, consumerSecret)
        outState.putLong(EXTRA_API_LAST_CHANGE, apiChangeTimestamp)
        super.onSaveInstanceState(outState)
    }


    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
        setSignInButton()
    }

    internal fun doLogin() {
        if (signInTask != null && signInTask!!.status == AsyncTask.Status.RUNNING) {
            signInTask!!.cancel(true)
        }
        setDefaultAPI()
        if (authType == Credentials.Type.OAUTH && editUsername.length() <= 0) {
            openBrowserLogin()
            return
        }
        val consumerKey = MicroBlogAPIFactory.getOAuthToken(this.consumerKey, consumerSecret)
        val apiUrlFormat = if (TextUtils.isEmpty(this.apiUrlFormat)) DEFAULT_TWITTER_API_URL_FORMAT else this.apiUrlFormat!!
        val username = editUsername.text.toString()
        val password = editPassword.text.toString()
        signInTask = SignInTask(this, username, password, authType, consumerKey, apiUrlFormat,
                sameOAuthSigningUrl, noVersionSuffix)
        AsyncTaskUtils.executeTask<AbstractSignInTask, Any>(signInTask)
    }

    private fun doBrowserLogin(intent: Intent?) {
        if (intent == null) return
        if (signInTask != null && signInTask!!.status == AsyncTask.Status.RUNNING) {
            signInTask!!.cancel(true)
        }
        setDefaultAPI()
        val verifier = intent.getStringExtra(EXTRA_OAUTH_VERIFIER)
        val consumerKey = MicroBlogAPIFactory.getOAuthToken(this.consumerKey, consumerSecret)
        val requestToken = OAuthToken(intent.getStringExtra(EXTRA_REQUEST_TOKEN),
                intent.getStringExtra(EXTRA_REQUEST_TOKEN_SECRET))
        val apiUrlFormat = if (TextUtils.isEmpty(this.apiUrlFormat)) DEFAULT_TWITTER_API_URL_FORMAT else this.apiUrlFormat!!
        signInTask = BrowserSignInTask(this, consumerKey, requestToken, verifier, apiUrlFormat,
                sameOAuthSigningUrl, noVersionSuffix)
        AsyncTaskUtils.executeTask<AbstractSignInTask, Any>(signInTask)
    }


    private fun setDefaultAPI() {
        val apiLastChange = preferences.getLong(KEY_API_LAST_CHANGE, apiChangeTimestamp)
        val defaultApiChanged = apiLastChange != apiChangeTimestamp
        val apiUrlFormat = Utils.getNonEmptyString(preferences, KEY_API_URL_FORMAT, DEFAULT_TWITTER_API_URL_FORMAT)
        val authType = AccountUtils.getCredentialsType(preferences.getInt(KEY_AUTH_TYPE, AuthTypeInt.OAUTH))
        val sameOAuthSigningUrl = preferences.getBoolean(KEY_SAME_OAUTH_SIGNING_URL, false)
        val noVersionSuffix = preferences.getBoolean(KEY_NO_VERSION_SUFFIX, false)
        val consumerKey = Utils.getNonEmptyString(preferences, KEY_CONSUMER_KEY, TWITTER_CONSUMER_KEY)
        val consumerSecret = Utils.getNonEmptyString(preferences, KEY_CONSUMER_SECRET, TWITTER_CONSUMER_SECRET)
        if (TextUtils.isEmpty(this.apiUrlFormat) || defaultApiChanged) {
            this.apiUrlFormat = apiUrlFormat
        }
        if (defaultApiChanged) {
            this.authType = authType
        }
        if (defaultApiChanged) {
            this.sameOAuthSigningUrl = sameOAuthSigningUrl
        }
        if (defaultApiChanged) {
            this.noVersionSuffix = noVersionSuffix
        }
        if (TextUtils.isEmpty(this.consumerKey) || defaultApiChanged) {
            this.consumerKey = consumerKey
        }
        if (TextUtils.isEmpty(this.consumerSecret) || defaultApiChanged) {
            this.consumerSecret = consumerSecret
        }
        if (defaultApiChanged) {
            apiChangeTimestamp = apiLastChange
        }
    }

    private fun setSignInButton() {
        when (authType) {
            Credentials.Type.XAUTH, Credentials.Type.BASIC -> {
                passwordSignIn.visibility = View.GONE
                signIn.isEnabled = editPassword.text.isNotEmpty() && editUsername.text.isNotEmpty()
            }
            Credentials.Type.OAUTH -> {
                passwordSignIn.visibility = View.VISIBLE
                signIn.isEnabled = true
            }
            else -> {
                passwordSignIn.visibility = View.GONE
                signIn.isEnabled = true
            }
        }
    }

    internal fun onSignInResult(result: SignInResponse) {
        dismissDialogFragment(FRAGMENT_TAG_SIGN_IN_PROGRESS)
        val am = AccountManager.get(this)
        setSignInButton()
        if (result.alreadyLoggedIn) {
            result.updateAccount(am)
            Toast.makeText(this, R.string.error_already_logged_in, Toast.LENGTH_SHORT).show()
        } else {
            result.insertAccount(am)
            val intent = Intent(this, HomeActivity::class.java)
            //TODO refresh time lines
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(intent)
            finish()
        }
    }

    internal fun onSignInError(exception: Exception) {
        if (BuildConfig.DEBUG) {
            Log.w(LOGTAG, exception)
        }
        if (exception is AuthenticityTokenException) {
            Toast.makeText(this, R.string.wrong_api_key, Toast.LENGTH_SHORT).show()
        } else if (exception is WrongUserPassException) {
            Toast.makeText(this, R.string.wrong_username_password, Toast.LENGTH_SHORT).show()
        } else if (exception is SignInTask.WrongBasicCredentialException) {
            Toast.makeText(this, R.string.wrong_username_password, Toast.LENGTH_SHORT).show()
        } else if (exception is SignInTask.WrongAPIURLFormatException) {
            Toast.makeText(this, R.string.wrong_api_key, Toast.LENGTH_SHORT).show()
        } else if (exception is LoginVerificationException) {
            Toast.makeText(this, R.string.login_verification_failed, Toast.LENGTH_SHORT).show()
        } else if (exception is AuthenticationException) {
            Utils.showErrorMessage(this, getString(R.string.action_signing_in), exception.cause, true)
        } else {
            Utils.showErrorMessage(this, getString(R.string.action_signing_in), exception, true)
        }
    }

    internal fun dismissDialogFragment(tag: String) {
        executeAfterFragmentResumed {
            val fm = supportFragmentManager
            val f = fm.findFragmentByTag(tag)
            if (f is DialogFragment) {
                f.dismiss()
            }
            Unit
        }
    }

    internal fun onSignInStart() {
        showSignInProgressDialog()
    }

    internal fun showSignInProgressDialog() {
        executeAfterFragmentResumed {
            if (isFinishing) return@executeAfterFragmentResumed
            val fm = supportFragmentManager
            val ft = fm.beginTransaction()
            val fragment = ProgressDialogFragment()
            fragment.isCancelable = false
            fragment.show(ft, FRAGMENT_TAG_SIGN_IN_PROGRESS)
        }
    }


    internal fun setUsernamePassword(username: String, password: String) {
        editUsername.setText(username)
        editPassword.setText(password)
    }

    internal abstract class AbstractSignInTask(activity: SignInActivity) : AsyncTask<Any, Runnable, SingleResponse<SignInResponse>>() {

        protected val activityRef: WeakReference<SignInActivity>

        init {
            this.activityRef = WeakReference(activity)
        }

        override final fun doInBackground(vararg args: Any?): SingleResponse<SignInResponse> {
            try {
                return SingleResponse.getInstance(performLogin())
            } catch (e: Exception) {
                return SingleResponse.getInstance(e)
            }
        }

        abstract fun performLogin(): SignInResponse

        override fun onPostExecute(result: SingleResponse<SignInResponse>) {
            val activity = activityRef.get()
            if (result.hasData()) {
                activity?.onSignInResult(result.data!!)
            } else {
                activity?.onSignInError(result.exception!!)
            }
        }

        override fun onPreExecute() {
            val activity = activityRef.get()
            activity?.onSignInStart()
        }

        override fun onProgressUpdate(vararg values: Runnable) {
            for (value in values) {
                value.run()
            }
        }

        @Throws(MicroBlogException::class)
        internal fun analyseUserProfileColor(user: User?): Int {
            if (user == null) throw MicroBlogException("Unable to get user info")
            return ParseUtils.parseColor("#" + user.profileLinkColor, Color.TRANSPARENT)
        }

    }

    /**
     * Created by mariotaku on 16/7/7.
     */
    internal class BrowserSignInTask(
            context: SignInActivity,
            private val consumerKey: OAuthToken,
            private val requestToken: OAuthToken,
            private val oauthVerifier: String?,
            private val apiUrlFormat: String,
            private val sameOAuthSigningUrl: Boolean,
            private val noVersionSuffix: Boolean
    ) : AbstractSignInTask(context) {

        private val context: Context

        init {
            this.context = context
        }

        @Throws(Exception::class)
        override fun performLogin(): SignInResponse {
            val versionSuffix = if (noVersionSuffix) null else "1.1"
            var endpoint = MicroBlogAPIFactory.getOAuthSignInEndpoint(apiUrlFormat,
                    sameOAuthSigningUrl)
            val oauth = MicroBlogAPIFactory.getInstance(context, endpoint,
                    OAuthAuthorization(consumerKey.oauthToken,
                            consumerKey.oauthTokenSecret), TwitterOAuth::class.java)
            val accessToken: OAuthToken
            if (oauthVerifier != null) {
                accessToken = oauth.getAccessToken(requestToken, oauthVerifier)
            } else {
                accessToken = oauth.getAccessToken(requestToken)
            }
            val auth = OAuthAuthorization(consumerKey.oauthToken,
                    consumerKey.oauthTokenSecret, accessToken)
            endpoint = MicroBlogAPIFactory.getOAuthEndpoint(apiUrlFormat, "api", versionSuffix,
                    sameOAuthSigningUrl)
            val twitter = MicroBlogAPIFactory.getInstance(context, endpoint, auth, MicroBlog::class.java)
            val apiUser = twitter.verifyCredentials()
            var color = analyseUserProfileColor(apiUser)
            val accountType = SignInActivity.detectAccountType(twitter, apiUser)
            val userId = apiUser.id!!
            val accountKey = UserKey(userId, UserKeyUtils.getUserHost(apiUser))
            val user = ParcelableUserUtils.fromUser(apiUser, accountKey)
            val account = AccountUtils.getAccountDetails(AccountManager.get(context), accountKey)
            if (account != null) {
                color = account.color
            }
            val credentials = OAuthCredentials()
            credentials.api_url_format = apiUrlFormat
            credentials.no_version_suffix = noVersionSuffix

            credentials.same_oauth_signing_url = sameOAuthSigningUrl

            credentials.consumer_key = consumerKey.oauthToken
            credentials.consumer_secret = consumerKey.oauthTokenSecret
            credentials.access_token = accessToken.oauthToken
            credentials.access_token_secret = accessToken.oauthTokenSecret

            return SignInResponse(account != null, Credentials.Type.OAUTH, credentials, user, color,
                    accountType)
        }
    }

    /**
     * Created by mariotaku on 16/7/7.
     */
    class InputLoginVerificationDialogFragment : BaseDialogFragment(), DialogInterface.OnClickListener, DialogInterface.OnShowListener {

        private var callback: SignInTask.InputLoginVerificationCallback? = null
        var challengeType: String? = null

        internal fun setCallback(callback: SignInTask.InputLoginVerificationCallback) {
            this.callback = callback
        }


        override fun onCancel(dialog: DialogInterface?) {
            callback!!.challengeResponse = null
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.login_verification)
            builder.setView(R.layout.dialog_login_verification_code)
            builder.setPositiveButton(android.R.string.ok, this)
            builder.setNegativeButton(android.R.string.cancel, this)
            val dialog = builder.create()
            dialog.setOnShowListener(this)
            return dialog
        }

        override fun onClick(dialog: DialogInterface, which: Int) {
            when (which) {
                DialogInterface.BUTTON_POSITIVE -> {
                    val alertDialog = dialog as AlertDialog
                    val editVerification = (alertDialog.findViewById(R.id.edit_verification_code) as EditText?)!!
                    callback!!.challengeResponse = ParseUtils.parseString(editVerification.text)
                }
                DialogInterface.BUTTON_NEGATIVE -> {
                    callback!!.challengeResponse = null
                }
            }
        }

        override fun onShow(dialog: DialogInterface) {
            val alertDialog = dialog as AlertDialog
            val verificationHint = alertDialog.findViewById(R.id.verification_hint) as TextView?
            val editVerification = alertDialog.findViewById(R.id.edit_verification_code) as EditText?
            if (verificationHint == null || editVerification == null) return
            when {
                "Push".equals(challengeType, ignoreCase = true) -> {
                    verificationHint.setText(R.string.login_verification_push_hint)
                    editVerification.visibility = View.GONE
                }
                "RetypePhoneNumber".equals(challengeType, ignoreCase = true) -> {
                    verificationHint.setText(R.string.login_challenge_retype_phone_hint)
                    editVerification.inputType = InputType.TYPE_CLASS_PHONE
                    editVerification.visibility = View.VISIBLE
                }
                "RetypeEmail".equals(challengeType, ignoreCase = true) -> {
                    verificationHint.setText(R.string.login_challenge_retype_email_hint)
                    editVerification.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                    editVerification.visibility = View.VISIBLE
                }
                "Sms".equals(challengeType, ignoreCase = true) -> {
                    verificationHint.setText(R.string.login_verification_pin_hint)
                    editVerification.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    editVerification.visibility = View.VISIBLE
                }
                else -> {
                    verificationHint.text = getString(R.string.unsupported_login_verification_type_name,
                            challengeType)
                    editVerification.visibility = View.VISIBLE
                }
            }
        }
    }

    class PasswordSignInDialogFragment : BaseDialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(context)
            builder.setView(R.layout.dialog_password_sign_in)
            builder.setPositiveButton(R.string.sign_in) { dialog, which ->
                val alertDialog = dialog as AlertDialog
                val editUsername = alertDialog.findViewById(R.id.username) as EditText?
                val editPassword = alertDialog.findViewById(R.id.password) as EditText?
                assert(editUsername != null && editPassword != null)
                val activity = activity as SignInActivity
                activity.setUsernamePassword(editUsername!!.text.toString(),
                        editPassword!!.text.toString())
                activity.doLogin()
            }
            builder.setNegativeButton(android.R.string.cancel, null)

            val alertDialog = builder.create()
            alertDialog.setOnShowListener { dialog ->
                val materialDialog = dialog as AlertDialog
                val editUsername = materialDialog.findViewById(R.id.username) as EditText?
                val editPassword = materialDialog.findViewById(R.id.password) as EditText?
                assert(editUsername != null && editPassword != null)
                val textWatcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {

                    }

                    override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                        val button = materialDialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return
                        button.isEnabled = editUsername!!.length() > 0 && editPassword!!.length() > 0
                    }

                    override fun afterTextChanged(s: Editable) {

                    }
                }

                editUsername!!.addTextChangedListener(textWatcher)
                editPassword!!.addTextChangedListener(textWatcher)
            }
            return alertDialog
        }
    }

    class SetConsumerKeySecretDialogFragment : BaseDialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(activity)
            builder.setView(R.layout.dialog_set_consumer_key_secret)
            builder.setPositiveButton(android.R.string.ok) { dialog, which ->
                val editConsumerKey = (dialog as Dialog).findViewById(R.id.editConsumerKey) as EditText
                val editConsumerSecret = dialog.findViewById(R.id.editConsumerSecret) as EditText
                val prefs = SharedPreferencesWrapper.getInstance(activity, SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putString(KEY_CONSUMER_KEY, ParseUtils.parseString(editConsumerKey.text))
                editor.putString(KEY_CONSUMER_SECRET, ParseUtils.parseString(editConsumerSecret.text))
                editor.apply()
            }
            val dialog = builder.create()
            dialog.setOnShowListener(DialogInterface.OnShowListener { dialog ->
                val activity = activity ?: return@OnShowListener
                val editConsumerKey = (dialog as Dialog).findViewById(R.id.editConsumerKey) as MaterialEditText
                val editConsumerSecret = dialog.findViewById(R.id.editConsumerSecret) as MaterialEditText
                editConsumerKey.addValidator(ConsumerKeySecretValidator(getString(R.string.invalid_consumer_key)))
                editConsumerSecret.addValidator(ConsumerKeySecretValidator(getString(R.string.invalid_consumer_secret)))
                val prefs = SharedPreferencesWrapper.getInstance(activity, SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE)
                editConsumerKey.setText(prefs.getString(KEY_CONSUMER_KEY, null))
                editConsumerSecret.setText(prefs.getString(KEY_CONSUMER_SECRET, null))
            })
            return dialog
        }
    }

    internal data class SignInResponse(
            val alreadyLoggedIn: Boolean,
            @Credentials.Type val credsType: String = Credentials.Type.EMPTY,
            val credentials: Credentials,
            val user: ParcelableUser,
            val color: Int = 0,
            val accountType: Pair<String, String>
    ) {

        private fun writeAccountInfo(map: MutableMap<String, String?>) {
            map[ACCOUNT_USER_DATA_KEY] = user.key.toString()
            map[ACCOUNT_USER_DATA_TYPE] = accountType.first
            map[ACCOUNT_USER_DATA_CREDS_TYPE] = credsType

            map[ACCOUNT_USER_DATA_ACTIVATED] = true.toString()
            map[ACCOUNT_USER_DATA_COLOR] = toHexColor(color)

            map[ACCOUNT_USER_DATA_USER] = LoganSquare.serialize(user)
            map[ACCOUNT_USER_DATA_EXTRAS] = accountType.second
        }

        private fun writeAuthToken(am: AccountManager, account: Account) {
            am.setAuthToken(account, ACCOUNT_AUTH_TOKEN_TYPE, LoganSquare.serialize(credentials))
        }

        fun updateAccount(am: AccountManager) {
            val account = AccountUtils.findByAccountKey(am, user.key) ?: return
            val map: MutableMap<String, String?> = HashMap()
            writeAccountInfo(map)
            for ((k, v) in map) {
                am.setUserData(account, k, v)
            }
            writeAuthToken(am, account)
        }

        fun insertAccount(am: AccountManager): Account {
            val account = Account(UserKey(user.screen_name, user.key.host).toString(), ACCOUNT_TYPE)
            val map: MutableMap<String, String?> = HashMap()
            writeAccountInfo(map)
            val userData = Bundle()
            for ((k, v) in map) {
                userData[k] = v
            }
            am.addAccountExplicitly(account, null, userData)
            return account
        }
    }

    internal class SignInTask(
            activity: SignInActivity,
            private val username: String,
            private val password: String,
            @Credentials.Type private val authType: String,
            private val consumerKey: OAuthToken,
            private val apiUrlFormat: String,
            private val sameOAuthSigningUrl: Boolean,
            private val noVersionSuffix: Boolean
    ) : AbstractSignInTask(activity) {
        private val verificationCallback: InputLoginVerificationCallback
        private val userAgent: String

        init {
            verificationCallback = InputLoginVerificationCallback()
            userAgent = UserAgentUtils.getDefaultUserAgentString(activity)
        }

        @Throws(Exception::class)
        override fun performLogin(): SignInResponse {
            when (authType) {
                Credentials.Type.OAUTH -> return authOAuth()
                Credentials.Type.XAUTH -> return authxAuth()
                Credentials.Type.BASIC -> return authBasic()
                Credentials.Type.EMPTY -> return authTwipOMode()
            }
            return authOAuth()
        }

        @Throws(OAuthPasswordAuthenticator.AuthenticationException::class, MicroBlogException::class)
        private fun authOAuth(): SignInResponse {
            val activity = activityRef.get() ?: throw InterruptedException()
            val endpoint = MicroBlogAPIFactory.getOAuthSignInEndpoint(apiUrlFormat,
                    sameOAuthSigningUrl)
            val auth = OAuthAuthorization(consumerKey.oauthToken,
                    consumerKey.oauthTokenSecret)
            val oauth = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, TwitterOAuth::class.java)
            val authenticator = OAuthPasswordAuthenticator(oauth,
                    verificationCallback, userAgent)
            val accessToken = authenticator.getOAuthAccessToken(username, password)
            val userId = accessToken.userId!!
            return getOAuthSignInResponse(activity, accessToken, userId,
                    Credentials.Type.OAUTH)
        }

        @Throws(MicroBlogException::class)
        private fun authxAuth(): SignInResponse {
            val activity = activityRef.get() ?: throw InterruptedException()
            var endpoint = MicroBlogAPIFactory.getOAuthSignInEndpoint(apiUrlFormat,
                    sameOAuthSigningUrl)
            var auth = OAuthAuthorization(consumerKey.oauthToken,
                    consumerKey.oauthTokenSecret)
            val oauth = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, TwitterOAuth::class.java)
            val accessToken = oauth.getAccessToken(username, password)
            var userId: String? = accessToken.userId
            if (userId == null) {
                // Trying to fix up userId if accessToken doesn't contain one.
                auth = OAuthAuthorization(consumerKey.oauthToken,
                        consumerKey.oauthTokenSecret, accessToken)
                endpoint = MicroBlogAPIFactory.getOAuthRestEndpoint(apiUrlFormat, sameOAuthSigningUrl,
                        noVersionSuffix)
                val microBlog = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, MicroBlog::class.java)
                userId = microBlog.verifyCredentials().id
            }
            return getOAuthSignInResponse(activity, accessToken, userId!!, Credentials.Type.XAUTH)
        }

        @Throws(MicroBlogException::class, OAuthPasswordAuthenticator.AuthenticationException::class)
        private fun authBasic(): SignInResponse {
            val activity = activityRef.get() ?: throw InterruptedException()
            val versionSuffix = if (noVersionSuffix) null else "1.1"
            val endpoint = Endpoint(MicroBlogAPIFactory.getApiUrl(apiUrlFormat, "api",
                    versionSuffix))
            val auth = BasicAuthorization(username, password)
            val twitter = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, MicroBlog::class.java)
            val apiUser: User
            try {
                apiUser = twitter.verifyCredentials()
            } catch (e: MicroBlogException) {
                if (e.statusCode == 401) {
                    throw WrongBasicCredentialException()
                } else if (e.statusCode == 404) {
                    throw WrongAPIURLFormatException()
                }
                throw e
            }

            val userId = apiUser.id!!
            var color = analyseUserProfileColor(apiUser)
            val accountType = SignInActivity.detectAccountType(twitter, apiUser)
            val accountKey = UserKey(userId, UserKeyUtils.getUserHost(apiUser))
            val user = ParcelableUserUtils.fromUser(apiUser, accountKey)
            val account = AccountUtils.getAccountDetails(AccountManager.get(activity), accountKey)
            if (account != null) {
                color = account.color
            }
            val credentials = BasicCredentials()
            credentials.api_url_format = apiUrlFormat
            credentials.no_version_suffix = noVersionSuffix
            credentials.username = username
            credentials.password = password
            return SignInResponse(account != null, Credentials.Type.BASIC, credentials, user,
                    color, accountType)
        }


        @Throws(MicroBlogException::class)
        private fun authTwipOMode(): SignInResponse {
            val activity = activityRef.get() ?: throw InterruptedException()
            val versionSuffix = if (noVersionSuffix) null else "1.1"
            val endpoint = Endpoint(MicroBlogAPIFactory.getApiUrl(apiUrlFormat, "api",
                    versionSuffix))
            val auth = EmptyAuthorization()
            val twitter = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, MicroBlog::class.java)
            val apiUser = twitter.verifyCredentials()
            val userId = apiUser.id!!
            var color = analyseUserProfileColor(apiUser)
            val accountType = SignInActivity.detectAccountType(twitter, apiUser)
            val accountKey = UserKey(userId, UserKeyUtils.getUserHost(apiUser))
            val user = ParcelableUserUtils.fromUser(apiUser, accountKey)
            val account = AccountUtils.getAccountDetails(AccountManager.get(activity), accountKey)
            if (account != null) {
                color = account.color
            }
            val credentials = EmptyCredentials()
            credentials.api_url_format = apiUrlFormat
            credentials.no_version_suffix = noVersionSuffix

            return SignInResponse(account != null, Credentials.Type.EMPTY, credentials, user, color,
                    accountType)
        }

        @Throws(MicroBlogException::class)
        private fun getOAuthSignInResponse(activity: SignInActivity,
                                           accessToken: OAuthToken,
                                           userId: String, @Credentials.Type authType: String): SignInResponse {
            val auth = OAuthAuthorization(consumerKey.oauthToken,
                    consumerKey.oauthTokenSecret, accessToken)
            val endpoint = MicroBlogAPIFactory.getOAuthRestEndpoint(apiUrlFormat,
                    sameOAuthSigningUrl, noVersionSuffix)
            val twitter = MicroBlogAPIFactory.getInstance(activity, endpoint, auth, MicroBlog::class.java)
            val apiUser = twitter.verifyCredentials()
            var color = analyseUserProfileColor(apiUser)
            val accountType = SignInActivity.detectAccountType(twitter, apiUser)
            val accountKey = UserKey(userId, UserKeyUtils.getUserHost(apiUser))
            val user = ParcelableUserUtils.fromUser(apiUser, accountKey)
            val account = AccountUtils.getAccountDetails(AccountManager.get(activity), accountKey)
            if (account != null) {
                color = account.color
            }
            val credentials = OAuthCredentials()
            credentials.api_url_format = apiUrlFormat
            credentials.no_version_suffix = noVersionSuffix

            credentials.same_oauth_signing_url = sameOAuthSigningUrl

            credentials.consumer_key = consumerKey.oauthToken
            credentials.consumer_secret = consumerKey.oauthTokenSecret
            credentials.access_token = accessToken.oauthToken
            credentials.access_token_secret = accessToken.oauthTokenSecret

            return SignInResponse(account != null, authType, credentials, user, color, accountType)
        }

        internal class WrongBasicCredentialException : OAuthPasswordAuthenticator.AuthenticationException()

        internal class WrongAPIURLFormatException : OAuthPasswordAuthenticator.AuthenticationException()

        internal inner class InputLoginVerificationCallback : OAuthPasswordAuthenticator.LoginVerificationCallback {

            var isChallengeFinished: Boolean = false

            var challengeResponse: String? = null
                set(value) {
                    isChallengeFinished = true
                    field = value
                }

            override fun getLoginVerification(challengeType: String): String? {
                // Dismiss current progress dialog
                publishProgress(Runnable {
                    val activity = activityRef.get() ?: return@Runnable
                    activity.dismissDialogFragment(SignInActivity.FRAGMENT_TAG_SIGN_IN_PROGRESS)
                })
                // Show verification input dialog and wait for user input
                publishProgress(Runnable {
                    val activity = activityRef.get() ?: return@Runnable
                    activity.executeAfterFragmentResumed { activity ->
                        val sia = activity as SignInActivity
                        val df = InputLoginVerificationDialogFragment()
                        df.isCancelable = false
                        df.setCallback(this@InputLoginVerificationCallback)
                        df.challengeType = challengeType
                        df.show(sia.supportFragmentManager, null)
                        Unit
                    }
                })
                while (!isChallengeFinished) {
                    // Wait for 50ms
                    try {
                        Thread.sleep(50)
                    } catch (e: InterruptedException) {
                        // Ignore
                    }

                }
                // Show progress dialog
                publishProgress(Runnable {
                    val activity = activityRef.get() ?: return@Runnable
                    activity.showSignInProgressDialog()
                })
                return challengeResponse
            }

        }

    }


    companion object {

        val FRAGMENT_TAG_SIGN_IN_PROGRESS = "sign_in_progress"
        private val TWITTER_SIGNUP_URL = "https://twitter.com/signup"
        private val EXTRA_API_LAST_CHANGE = "api_last_change"
        private val DEFAULT_TWITTER_API_URL_FORMAT = "https://[DOMAIN.]twitter.com/"

        internal fun detectAccountType(twitter: MicroBlog, user: User): Pair<String, String> {
            try {
                // Get StatusNet specific resource
                val config = twitter.statusNetConfig
                val extra = StatusNetAccountExtras()
                val site = config.site
                if (site != null) {
                    extra.textLimit = site.textLimit
                }
                return Pair.create<String, String>(AccountType.STATUSNET,
                        JsonSerializer.serialize(extra, StatusNetAccountExtras::class.java))
            } catch (e: MicroBlogException) {
                // Ignore
            }

            try {
                // Get Twitter official only resource
                val paging = Paging()
                paging.count(1)
                twitter.getActivitiesAboutMe(paging)
                val extra = TwitterAccountExtras()
                extra.setIsOfficialCredentials(true)
                return Pair.create<String, String>(AccountType.TWITTER,
                        JsonSerializer.serialize(extra, TwitterAccountExtras::class.java))
            } catch (e: MicroBlogException) {
                // Ignore
            }

            if (UserKeyUtils.isFanfouUser(user)) {
                return Pair.create<String, String>(AccountType.FANFOU, null)
            }
            return Pair.create<String, String>(AccountType.TWITTER, null)
        }
    }


}
