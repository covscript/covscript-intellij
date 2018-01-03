package org.covscript.lang

import com.intellij.CommonBundle
import com.intellij.ide.actions.CreateFileAction
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.psi.*
import java.time.LocalDate

class NewCovFile : CreateFileAction(CAPTION, "", COV_ICON) {
	private companion object Caption {
		private const val CAPTION = "CovScript File"
	}

	override fun getActionName(directory: PsiDirectory?, s: String?) = CAPTION
	override fun getErrorTitle(): String = CommonBundle.getErrorTitle()
	override fun getDefaultExtension() = COV_EXTENSION

	override fun create(name: String, directory: PsiDirectory): Array<PsiElement> {
		val fixedExtension = when (FileUtilRt.getExtension(name)) {
			COV_EXTENSION -> name
			else -> "$name.$COV_EXTENSION"
		}
		return arrayOf(directory.add(PsiFileFactory
				.getInstance(directory.project)
				.createFileFromText(fixedExtension, CovFileType, """//
// Created by ${System.getenv("USER")} on ${LocalDate.now()}
//

""")))
	}
}

