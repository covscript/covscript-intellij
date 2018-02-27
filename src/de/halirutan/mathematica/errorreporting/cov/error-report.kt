/*
 * Copyright (c) 2017 Patrick Scheibe
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package de.halirutan.mathematica.errorreporting.cov

import com.intellij.CommonBundle
import com.intellij.diagnostic.*
import com.intellij.errorreport.bean.ErrorBean
import com.intellij.ide.DataManager
import com.intellij.ide.plugins.PluginManager
import com.intellij.idea.IdeaLogger
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ex.ApplicationInfoEx
import com.intellij.openapi.diagnostic.*
import com.intellij.openapi.diagnostic.SubmittedReportInfo.SubmissionStatus
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.util.Consumer
import com.intellij.util.SystemProperties
import org.apache.commons.codec.binary.Base64
import org.covscript.lang.module.covSettings
import org.eclipse.egit.github.core.*
import org.eclipse.egit.github.core.client.GitHubClient
import org.eclipse.egit.github.core.service.IssueService
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey
import java.awt.Component
import java.io.IOException
import java.io.ObjectInputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private object AnonymousFeedback {
	private const val tokenFile = "de/halirutan/mathematica/errorreporting-cov/token.bin"
	private const val gitRepoUser = "covscript"
	private const val gitRepo = "covscript-intellij"
	private const val issueLabel = "pending"

	/**
	 * Makes a connection to GitHub. Checks if there is an issue that is a duplicate and based on this, creates either a
	 * new issue or comments on the duplicate (if the user provided additional information).
	 *
	 * @param environmentDetails Information collected by [getKeyValuePairs]
	 * @return The report info that is then used in [GitHubErrorReporter] to show the user a balloon with the link
	 * of the created issue.
	 */
	internal fun sendFeedback(environmentDetails: MutableMap<String, String>): SubmittedReportInfo {
		val logger = Logger.getInstance(javaClass.name)
		try {
			val resource: URL? = javaClass.classLoader.getResource(tokenFile)
			if (resource == null) {
				logger.info("Could not find token file")
				throw IOException("Could not decrypt access token")
			}
			val gitAccessToken = decrypt(resource)
			val client = GitHubClient()
			client.setOAuth2Token(gitAccessToken)
			val repoID = RepositoryId(gitRepoUser, gitRepo)
			val issueService = IssueService(client)
			var newGibHubIssue = createNewGibHubIssue(environmentDetails)
			val duplicate = findFirstDuplicate(newGibHubIssue.title, issueService, repoID)
			var isNewIssue = true
			if (duplicate != null) {
				issueService.createComment(repoID, duplicate.number, generateGitHubIssueBody(environmentDetails, false))
				newGibHubIssue = duplicate
				isNewIssue = false
			} else newGibHubIssue = issueService.createIssue(repoID, newGibHubIssue)
			return SubmittedReportInfo(newGibHubIssue.htmlUrl, ErrorReportBundle.message(
					if (isNewIssue) "git.issue.text" else "git.issue.duplicate.text", newGibHubIssue.htmlUrl, newGibHubIssue.number.toLong()),
					if (isNewIssue) SubmissionStatus.NEW_ISSUE else SubmissionStatus.DUPLICATE)
		} catch (e: Exception) {
			return SubmittedReportInfo(null,
					ErrorReportBundle.message("report.error.connection.failure"),
					SubmissionStatus.FAILED)
		}
	}

	private fun findFirstDuplicate(uniqueTitle: String, service: IssueService, repo: RepositoryId): Issue? {
		val searchParameters = HashMap<String, String>(2)
		searchParameters[IssueService.FILTER_STATE] = IssueService.STATE_OPEN
		return service.pageIssues(repo, searchParameters).flatMap { it }.firstOrNull { it.title == uniqueTitle }
	}

	private fun createNewGibHubIssue(details: MutableMap<String, String>) = Issue().apply {
		val errorMessage = details.remove("error.message")?.takeIf(String::isNotBlank) ?: "Unspecified error"
		title = ErrorReportBundle.message("git.issue.title", details.remove("error.hash").orEmpty(), errorMessage)
		body = generateGitHubIssueBody(details, true)
		labels = listOf(Label().apply { name = issueLabel })
	}

	private fun generateGitHubIssueBody(details: MutableMap<String, String>, includeStacktrace: Boolean): String {
		val errorDescription = details.remove("error.description").orEmpty()
		val stackTrace = details.remove("error.stacktrace")?.takeIf(String::isNotBlank) ?: "invalid stacktrace"
		val result = StringBuilder()
		if (!errorDescription.isEmpty()) {
			result.append(errorDescription)
			result.append("\n\n----------------------\n\n")
		}
		for ((key, value) in details) {
			result.append("- ")
			result.append(key)
			result.append(": ")
			result.append(value)
			result.append("\n")
		}
		if (includeStacktrace) {
			result.append("\n```\n")
			result.append(stackTrace)
			result.append("\n```\n")
		}
		return result.toString()
	}
}

private const val initVector = "RandomInitVector"
private const val key = "GitHubErrorToken"

private fun decrypt(file: URL): String {
	val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
	cipher.init(Cipher.DECRYPT_MODE,
			SecretKeySpec(key.toByteArray(charset("UTF-8")), "AES"),
			IvParameterSpec(initVector.toByteArray(charset("UTF-8"))))
	return String(cipher.doFinal(Base64.decodeBase64(ObjectInputStream(file.openStream()).readObject() as String)))
}

fun main(args: Array<String>) {
	if (args.size != 2) return
	Files.write(Paths.get(args[1]), encrypt(args[0]).toByteArray())
}

private fun encrypt(value: String): String {
	val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
	cipher.init(Cipher.ENCRYPT_MODE,
			SecretKeySpec(key.toByteArray(charset("UTF-8")), "AES"),
			IvParameterSpec(initVector.toByteArray(charset("UTF-8"))))
	return Base64.encodeBase64String(cipher.doFinal(value.toByteArray()))
}

class GitHubErrorReporter : ErrorReportSubmitter() {
	override fun getReportActionText() = ErrorReportBundle.message("report.error.to.plugin.vendor")
	override fun submit(
			events: Array<IdeaLoggingEvent>, info: String?, parent: Component, consumer: Consumer<SubmittedReportInfo>) =
			doSubmit(events[0], parent, consumer, GitHubErrorBean(events[0].throwable, IdeaLogger.ourLastActionId), info)

	private fun doSubmit(
			event: IdeaLoggingEvent,
			parent: Component,
			callback: Consumer<SubmittedReportInfo>,
			bean: GitHubErrorBean,
			description: String?): Boolean {
		val dataContext = DataManager.getInstance().getDataContext(parent)
		bean.description = description
		bean.message = event.message
		event.throwable?.let { throwable ->
			IdeErrorsDialog.findPluginId(throwable)?.let { pluginId ->
				PluginManager.getPlugin(pluginId)?.let { ideaPluginDescriptor ->
					if (!ideaPluginDescriptor.isBundled) {
						bean.pluginName = ideaPluginDescriptor.name
						bean.pluginVersion = ideaPluginDescriptor.version
					}
				}
			}
		}

		(event.data as? LogMessageEx)?.let { bean.attachments = it.includedAttachments }
		val project = CommonDataKeys.PROJECT.getData(dataContext)
		val reportValues = getKeyValuePairs(
				project,
				bean,
				ApplicationInfo.getInstance() as ApplicationInfoEx,
				ApplicationNamesInfo.getInstance())
		val notifyingCallback = CallbackWithNotification(callback, project)
		val task = AnonymousFeedbackTask(project, ErrorReportBundle.message(
				"report.error.progress.dialog.text"), true, reportValues, notifyingCallback)
		if (project == null) task.run(EmptyProgressIndicator()) else ProgressManager.getInstance().run(task)
		return true
	}

	internal class CallbackWithNotification(
			private val consumer: Consumer<SubmittedReportInfo>,
			private val project: Project?) : Consumer<SubmittedReportInfo> {
		override fun consume(reportInfo: SubmittedReportInfo) {
			consumer.consume(reportInfo)
			if (reportInfo.status == SubmissionStatus.FAILED) ReportMessages.GROUP.createNotification(ReportMessages.ERROR_REPORT,
					reportInfo.linkText, NotificationType.ERROR, null).setImportant(false).notify(project)
			else ReportMessages.GROUP.createNotification(ReportMessages.ERROR_REPORT, reportInfo.linkText,
					NotificationType.INFORMATION, NotificationListener.URL_OPENING_LISTENER).setImportant(false).notify(project)
		}
	}
}

/**
 * Extends the standard class to provide the hash of the thrown exception stack trace.
 *
 * @author patrick (17.06.17).
 */
class GitHubErrorBean(throwable: Throwable, lastAction: String) : ErrorBean(throwable, lastAction) {
	val exceptionHash = Arrays.hashCode(throwable.stackTrace).toString()
}

/**
 * Messages and strings used by the error reporter
 */
private object ErrorReportBundle {
	@NonNls private const val BUNDLE = "de.halirutan.mathematica.errorreporting-cov.report-bundle"
	private val bundle: ResourceBundle by lazy { ResourceBundle.getBundle(BUNDLE) }

	@JvmStatic
	internal fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
			CommonBundle.message(bundle, key, *params)
}

private class AnonymousFeedbackTask(
		project: Project?, title: String, canBeCancelled: Boolean,
		private val params: MutableMap<String, String>,
		private val callback: Consumer<SubmittedReportInfo>) : Task.Backgroundable(project, title, canBeCancelled) {
	override fun run(indicator: ProgressIndicator) {
		indicator.isIndeterminate = true
		callback.consume(AnonymousFeedback.sendFeedback(params))
	}
}

/**
 * Collects information about the running IDEA and the error
 */
private fun getKeyValuePairs(
		project: Project?,
		error: GitHubErrorBean,
		appInfo: ApplicationInfoEx,
		namesInfo: ApplicationNamesInfo): MutableMap<String, String> {
	val params = mutableMapOf(
			"error.description" to error.description,
			"CovScript Version" to (project?.run { covSettings.settings.version } ?: "Unknown"),
			"Plugin Name" to error.pluginName,
			"Plugin Version" to error.pluginVersion,
			"OS Name" to SystemProperties.getOsName(),
			"Java version" to SystemProperties.getJavaVersion(),
			"Java vm vendor" to SystemProperties.getJavaVmVendor(),
			"App Name" to namesInfo.productName,
			"App Full Name" to namesInfo.fullProductName,
			"App Version name" to appInfo.versionName,
			"Is EAP" to java.lang.Boolean.toString(appInfo.isEAP),
			"App Build" to appInfo.build.asString(),
			"App Version" to appInfo.fullVersion,
			"Last Action" to error.lastAction,
			"error.message" to error.message,
			"error.stacktrace" to error.stackTrace,
			"error.hash" to error.exceptionHash)
	for (attachment in error.attachments) {
		params["attachment.name"] = attachment.name
		params["attachment.value"] = attachment.encodedBytes
	}
	return params
}