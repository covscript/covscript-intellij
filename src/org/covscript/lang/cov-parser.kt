package org.covscript.lang

import com.intellij.lang.*
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.tree.*
import org.covscript.lang.psi.CovTypes

class CovParserDefinition : ParserDefinition {
	private companion object {
		private val FILE = IFileElementType(Language.findInstance(CovLanguage::class.java))
	}

	override fun createFile(viewProvider: FileViewProvider) = CovFile(viewProvider)
	override fun createParser(project: Project?) = CovParser()
	override fun spaceExistanceTypeBetweenTokens(left: ASTNode?, right: ASTNode?) = ParserDefinition.SpaceRequirements.MAY
	override fun createLexer(project: Project?) = CovLexerAdapter()
	override fun getFileNodeType() = FILE
	override fun createElement(astNode: ASTNode?): PsiElement = CovTypes.Factory.createElement(astNode)
	override fun getStringLiteralElements() = CovTokenType.STRINGS
	override fun getCommentTokens() = CovTokenType.COMMENTS
	override fun getWhitespaceTokens(): TokenSet = TokenSet.WHITE_SPACE
}

class CovTokenType(debugName: String) : IElementType(debugName, CovLanguage) {
	companion object {
		@JvmField val COMMENTS = TokenSet.create(CovTypes.LINE_COMMENT, CovTypes.COMMENT)
		@JvmField val SYMBOLS = TokenSet.create(CovTypes.SYMBOL, CovTypes.PARAMETER)
		@JvmField val STRINGS = TokenSet.create(CovTypes.STR, CovTypes.CHAR, CovTypes.STRING, CovTypes.CHAR_LITERAL)
		@JvmField val CONCATENATABLE_TOKENS = TokenSet.orSet(COMMENTS, STRINGS)
		fun fromText(name: String, project: Project): PsiElement = PsiFileFactory
				.getInstance(project)
				.createFileFromText(CovLanguage, name)
				.firstChild
	}
}

class CovElementType(debugName: String) : IElementType(debugName, CovLanguage)
