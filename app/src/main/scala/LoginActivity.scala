package fazzdev.loginactivityscala

import android.Manifest.permission.READ_CONTACTS
import android.app.LoaderManager.LoaderCallbacks
import android.content.pm.PackageManager
import android.content.{CursorLoader, Loader}
import android.database.Cursor
import android.net.Uri
import android.os.{Build, Bundle}
import android.provider.ContactsContract
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.{KeyEvent, View}
import android.widget.{ArrayAdapter, AutoCompleteTextView, Button, EditText, TextView}
import fazzdev.loginactivityscala.ViewExtensions._

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent._
import scala.util.{Failure, Success}

object LoginActivity {
  /**
   * Id to identity READ_CONTACTS permission request.
   */
  private val REQUEST_READ_CONTACTS = 0
  /**
   * A dummy authentication store containing known user names and passwords.
   * TODO: remove after connecting to a real authentication system.
   */
  private val DUMMY_CREDENTIALS = Map(
    "foo@example.com" -> "hello",
    "bar@example.com" -> "world")

  private object ProfileQuery {
    val MIMETYPE = "mimetype"
    val ADDRESS = "data1"
    val ISPRIMARY = "is_primary"
    val PROJECTION = Array(ADDRESS, ISPRIMARY)
    val ADDRESSID = 0
    val ISPRIMARYID = 1
  }
}

/**
 * A login screen that offers login via email/password.
 */
class LoginActivity extends AppCompatActivity with LoaderCallbacks[Cursor] with RichActivity {
  // UI references.
  lazy val emailView = findView[AutoCompleteTextView](R.id.email)
  lazy val passwordView = findView[EditText](R.id.password)
  lazy val loginFormView = findView[View](R.id.login_form)
  lazy val progressView = findView[View](R.id.login_progress)

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_login)
    // Set up the login form.
    populateAutoComplete()

    passwordView.onEditorAction { (textView: TextView, id: Int, keyEvent: KeyEvent) =>
      val requireLogin = id == R.id.login || id == EditorInfo.IME_NULL
      if (requireLogin) attemptLogin()
      requireLogin
    }

    val emailSignInButton = findView[Button](R.id.email_sign_in_button)
    emailSignInButton.onClick { _ => attemptLogin() }
  }

  def populateAutoComplete() = if (mayRequestContacts()) getLoaderManager.initLoader(0, null, this)

  def mayRequestContacts() = {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
      true
    else if (checkSelfPermission(READ_CONTACTS) == PackageManager.PERMISSION_GRANTED)
      true
    else if (shouldShowRequestPermissionRationale(READ_CONTACTS)) {
      Snackbar.make(emailView, R.string.permission_rationale, Snackbar.LENGTH_INDEFINITE)
        .onOkClick { _ => requestPermissions(Array(READ_CONTACTS), LoginActivity.REQUEST_READ_CONTACTS) }
      false
    }
    else {
      requestPermissions(Array(READ_CONTACTS), LoginActivity.REQUEST_READ_CONTACTS)
      false
    }
  }

  /**
   * Attempts to sign in or register the account specified by the login form.
   * If there are form errors (invalid email, missing fields, etc.), the
   * errors are presented and no actual login attempt is made.
   */
  private def attemptLogin() {
    // Reset errors.
    emailView.setError(null)
    passwordView.setError(null)

    // Store values at the time of the login attempt.
    val email = emailView.getText.toString
    val password = passwordView.getText.toString

    // Check for a valid email address.
    // Check for a valid password, if the user entered one.
    val focusView =
      if (TextUtils.isEmpty(email)) {
        emailView.setError(getString(R.string.error_field_required))
        emailView
      }
      else if (!isEmailValid(email)) {
        emailView.setError(getString(R.string.error_invalid_email))
        emailView
      }
      else if (!TextUtils.isEmpty(password) && !isPasswordValid(password)) {
        passwordView.setError(getString(R.string.error_invalid_password))
        passwordView
      }
      else null

    if (focusView != null) {
      // There was an error; don't attempt login and focus the first
      // form field with an error.
      focusView.requestFocus
    }
    else {
      // Show a progress spinner, and kick off a background task to
      // perform the user login attempt.
      showProgress(true)

      val authFuture = Future {

        val result = LoginActivity.DUMMY_CREDENTIALS.find(_._1.equalsIgnoreCase(email))
        result match {
          // Account exists, return true if the password matches.
          case Some(credential) => credential._2.equals(password)
          // TODO: register the new account here.
          case _ => false
        }
      }

      authFuture onComplete {
        case Success(success) =>
          runOnUiThread { showProgress(false) }

          if (success) {
            finish()
          } else {
            runOnUiThread { showPasswordError() }
          }
        case Failure(exception) =>
          runOnUiThread { showProgress(false) }

          println("An error has occured: " + exception.getMessage)
      }
    }
  }

  //TODO: Replace this with your own logic
  def isEmailValid(email: String) = email.contains("@")

  def isPasswordValid(password: String) = password.length() > 4

  /**
   * Shows the progress UI and hides the login form.
   */
  def showProgress(show: Boolean) {
    loginFormView.setVisibility(!show)
    progressView.setVisibility(show)
  }

  def showPasswordError() {
    passwordView.setError(getString(R.string.error_incorrect_password))
    passwordView.requestFocus
  }


  /**
   * Callback received when a permissions request has been completed.
   */
  override def onRequestPermissionsResult(requestCode: Int, permissions: Array[String],
                                          grantResults: Array[Int]) {
    if (requestCode == LoginActivity.REQUEST_READ_CONTACTS &&
      grantResults.length == 1 &&
      grantResults(0) == PackageManager.PERMISSION_GRANTED) {
      populateAutoComplete()
    }
  }


  override def onCreateLoader(i: Int, bundle: Bundle): Loader[Cursor] = {
    import ContactsContract.CommonDataKinds.Email
    import ContactsContract.Contacts.Data
    import ContactsContract.Profile
    import LoginActivity.ProfileQuery

    new CursorLoader(this,
      // Retrieve data rows for the device user's 'profile' contact.
      Uri.withAppendedPath(Profile.CONTENT_URI, Data.CONTENT_DIRECTORY),
      ProfileQuery.PROJECTION,
      // Select only email addresses.
      s"${ProfileQuery.MIMETYPE} = ?",
      Array(Email.CONTENT_ITEM_TYPE),
      // Show primary email addresses first. Note that there won't be
      // a primary email address if the user hasn't specified one.
      s"${ProfileQuery.ISPRIMARY} DESC")
  }

  override def onLoadFinished(cursorLoader: Loader[Cursor], cursor: Cursor) {
    import LoginActivity.ProfileQuery

    val emails = new ArrayBuffer[String]
    cursor.moveToFirst
    while (!cursor.isAfterLast) {
      emails += cursor.getString(ProfileQuery.ADDRESSID)
      cursor.moveToNext
    }

    addEmailsToAutoComplete(emails)
  }

  def addEmailsToAutoComplete(emailAddresses: ArrayBuffer[String]) {
    //Create adapter to tell the AutoCompleteTextView what to show in its dropdown list.
    val adapter = new ArrayAdapter[String](
      LoginActivity.this,
      android.R.layout.simple_dropdown_item_1line,
      emailAddresses.asJava)

    emailView.setAdapter(adapter)
  }

  override def onLoaderReset(cursorLoader: Loader[Cursor]) {
  }
}
