package org.covscript.lang.editing

import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.ide.IconProvider
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.lang.*
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.parser.GeneratedParserUtilBase
import com.intellij.lang.refactoring.NamesValidator
import com.intellij.navigation.LocationPresentation
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PatternCondition
import com.intellij.patterns.PlatformPatterns
import com.intellij.pom.PomTargetPsiElement
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.ProcessingContext
import icons.CovIcons
import org.covscript.lang.*
import org.covscript.lang.psi.*
import javax.swing.Icon

class CovIconProvider : IconProvider() {
	override fun getIcon(element: PsiElement, flags: Int): Icon? {
		val file = element as? PsiFile ?: return null
		return when (file.virtualFile?.fileType) {
			CovPackageFileType -> icon(file) ?: CovIcons.COV_PKG_ICON
			CovFileType -> icon(file) ?: CovIcons.COV_ICON
			else -> null
		}
	}

	private fun icon(file: PsiFile) =
			icon(file.children.filterIsInstance<CovStatement>().mapNotNull { it.inside })

	private fun icon(validChildren: List<PsiElement>) = if (validChildren.size == 1)
		when (validChildren.first()) {
			is CovNamespaceDeclaration -> CovIcons.NAMESPACE_ICON
			is CovStructDeclaration -> CovIcons.STRUCT_ICON
			is CovFunctionDeclaration -> CovIcons.FUNCTION_ICON
			else -> null
		}
	else null
}

class CovBraceMatcher : PairedBraceMatcher {
	companion object {
		@JvmField
		val PAIRS = arrayOf(
				BracePair(CovTypes.LEFT_BRACKET, CovTypes.RIGHT_BRACKET, false),
				BracePair(CovTypes.LEFT_B_BRACKET, CovTypes.RIGHT_B_BRACKET, false),
				BracePair(CovTypes.LEFT_S_BRACKET, CovTypes.RIGHT_S_BRACKET, false),
				BracePair(CovTypes.FUNCTION_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.NAMESPACE_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.WHILE_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.FOR_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.IF_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.STRUCT_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.TRY_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.LOOP_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.BLOCK_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.SWITCH_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.CASE_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.DEFAULT_KEYWORD, CovTypes.END_KEYWORD, false),
				BracePair(CovTypes.COLLAPSER_BEGIN, CovTypes.COLLAPSER_END, false))
	}

	override fun getCodeConstructStart(psiFile: PsiFile?, openingBraceOffset: Int) = openingBraceOffset
	override fun isPairedBracesAllowedBeforeType(type: IElementType, iElementType: IElementType?) = true
	override fun getPairs() = PAIRS
}

class CovCommenter : Commenter {
	override fun getCommentedBlockCommentPrefix() = blockCommentPrefix
	override fun getCommentedBlockCommentSuffix() = blockCommentSuffix
	override fun getBlockCommentPrefix(): String? = null
	override fun getBlockCommentSuffix(): String? = null
	override fun getLineCommentPrefix() = "# "
}

class CovEnterHandlerDelegate : EnterHandlerDelegateAdapter() {
	override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
		if (file.language != CovLanguage.INSTANCE) return EnterHandlerDelegate.Result.Continue
		val caretModel = editor.caretModel
		val offset = caretModel.offset
		val element = file.findElementAt(offset) ?: return EnterHandlerDelegate.Result.Continue
		if (element.node.elementType != CovTypes.EOL) return EnterHandlerDelegate.Result.Continue
		var prevLeaf = PsiTreeUtil.getPrevSiblingOfType(element, LeafPsiElement::class.java)
				?: return EnterHandlerDelegate.Result.Continue
		while (prevLeaf.node.elementType.let { it == CovTypes.EOL || it == CovTypes.SYM || it == TokenType.WHITE_SPACE })
			prevLeaf = PsiTreeUtil.getPrevSiblingOfType(prevLeaf, LeafPsiElement::class.java)
					?: return EnterHandlerDelegate.Result.Continue
		return CovBraceMatcher.PAIRS.firstOrNull { it.leftBraceType == prevLeaf.elementType }?.also {
			editor.document.insertString(offset, when (it.rightBraceType) {
				CovTypes.END_KEYWORD -> "\t\nend\n"
				CovTypes.COLLAPSER_END -> "\t\n@end\n"
				else -> return EnterHandlerDelegate.Result.Continue
			})
			caretModel.moveToOffset(offset + 1)
		}?.let { EnterHandlerDelegate.Result.Stop } ?: EnterHandlerDelegate.Result.Continue
	}
}

class CovSpellCheckingStrategy : SpellcheckingStrategy() {
	override fun getTokenizer(element: PsiElement): Tokenizer<*> = when {
		element is PsiComment -> super.getTokenizer(element)
		element is CovSymbol && element.isDeclaration -> super.getTokenizer(element)
		element is CovString -> super.getTokenizer(element).takeIf { it != EMPTY_TOKENIZER } ?: TEXT_TOKENIZER
		else -> EMPTY_TOKENIZER
	}
}

class CovNamesValidator : NamesValidator, RenameInputValidator {
	companion object : PatternCondition<PsiElement>("") {
		override fun accepts(element: PsiElement, context: ProcessingContext?) =
				(element as? PomTargetPsiElement)?.navigationElement is CovSymbol
	}

	override fun isKeyword(s: String, project: Project?) = s in COV_KEYWORDS
	override fun isInputValid(name: String, ele: PsiElement, context: ProcessingContext) =
			isIdentifier(name, ele.project) && !isKeyword(name, ele.project)

	override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement().with(Companion)
	override fun isIdentifier(name: String, project: Project?) = name.isNotBlank() and
			name.all { it.isLetterOrDigit() || it == '_' } and
			!name.first().isDigit() and
			!isKeyword(name, project)
}

object CovFileNameValidator : InputValidatorEx {
	override fun canClose(inputString: String?) = checkInput(inputString)
	override fun getErrorText(inputString: String?) = CovBundle.message("cov.actions.new-file.invalid", inputString.orEmpty())
	override fun checkInput(inputString: String?) = inputString?.run {
		all { it.isLetterOrDigit() || it == '_' } and
				!first().isDigit() and
				(this !in COV_KEYWORDS)
	}.orFalse()
}

const val TEXT_MAX = 16
const val LONG_TEXT_MAX = 24
private fun cutText(it: String, textMax: Int) = if (it.length <= textMax) it else "${it.take(textMax)}…"
private val PsiElement.isBlockStructure
	get() = this is CovBlockStatement ||
			this is CovNamespaceDeclaration ||
			this is CovFunctionDeclaration ||
			this is CovStructDeclaration ||
			this is CovForStatement ||
			this is CovCollapsedStatement ||
			this is CovTryCatchStatement ||
			this is CovSwitchStatement ||
			this is CovWhileStatement ||
			this is CovLoopUntilStatement ||
			this is CovBlockStatement ||
			this is CovIfStatement ||
			this is CovArrayLit

class CovBreadCrumbProvider : BreadcrumbsProvider {
	override fun getLanguages() = arrayOf(CovLanguage.INSTANCE)
	override fun acceptElement(o: PsiElement) = o.isBlockStructure
	override fun getElementTooltip(o: PsiElement) = when (o) {
		is CovFunctionDeclaration -> "function: <${o.text}>"
		is CovStructDeclaration -> "struct: <${o.text}>"
		is CovNamespaceDeclaration -> "namespace: <${o.text}>"
		else -> null
	}

	override fun getElementInfo(o: PsiElement): String = cutText(when (o) {
		is PsiNameIdentifierOwner -> o.nameIdentifier?.text.orEmpty()
		is CovForStatement -> "for ${o.symbol?.text}"
		is CovArrayLit -> "array literal"
		is CovLoopUntilStatement -> "loop ${o.expr}"
		is CovWhileStatement -> "while ${o.expr}"
		is CovTryCatchStatement -> "try catch ${o.symbol}"
		is CovSwitchStatement -> "switch statement"
		is CovCollapsedStatement -> "collapsed block"
		is CovBlockStatement -> "begin block"
		is CovIfStatement -> "if ${o.expr}"
		else -> "??"
	}, TEXT_MAX)
}

class CovFoldingBuilder : FoldingBuilderEx() {
	override fun getPlaceholderText(node: ASTNode): String = node.elementType.let { o ->
		cutText(when (o) {
			CovTypes.FUNCTION_DECLARATION -> "function…"
			CovTypes.STRUCT_DECLARATION -> "struct…"
			CovTypes.NAMESPACE_DECLARATION -> "namespace…"
			CovTypes.FOR_STATEMENT -> "for…"
			CovTypes.LOOP_UNTIL_STATEMENT -> "loop…"
			CovTypes.WHILE_STATEMENT -> "while…"
			CovTypes.TRY_CATCH_STATEMENT -> "try…catch…"
			CovTypes.SWITCH_STATEMENT -> "switch…"
			CovTypes.COLLAPSED_STATEMENT -> "@begin…"
			CovTypes.BLOCK_STATEMENT -> "begin…"
			CovTypes.IF_STATEMENT -> "if…"
			CovTypes.ARRAY_LIT -> "{…}"
			else -> "??"
		}, TEXT_MAX)
	}

	override fun isCollapsedByDefault(node: ASTNode) = false
	override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean) = SyntaxTraverser
			.psiTraverser(root)
			.forceDisregardTypes(GeneratedParserUtilBase.DUMMY_BLOCK::equals)
			.traverse()
			.filter(PsiElement::isBlockStructure)
			.map { FoldingDescriptor(it, it.textRange) }
			.toList()
			.toTypedArray()
}

class CovStructureViewFactory : PsiStructureViewFactory {
	override fun getStructureViewBuilder(psiFile: PsiFile) = object : TreeBasedStructureViewBuilder() {
		override fun createStructureViewModel(editor: Editor?) = CovModel(psiFile, editor)
		override fun isRootNodeShown() = true
	}

	private class CovModel(file: PsiFile, editor: Editor?) :
			StructureViewModelBase(file, editor, CovStructureElement(file)),
			StructureViewModel.ElementInfoProvider {
		init {
			withSuitableClasses(
					CovBlockStatement::class.java,
					CovNamespaceDeclaration::class.java,
					CovFunctionDeclaration::class.java,
					CovStructDeclaration::class.java,
					CovForStatement::class.java,
					CovCollapsedStatement::class.java,
					CovTryCatchStatement::class.java,
					CovSwitchStatement::class.java,
					CovWhileStatement::class.java,
					CovLoopUntilStatement::class.java,
					CovBlockStatement::class.java,
					CovIfStatement::class.java,
					CovArrayLit::class.java)
		}

		override fun isAlwaysShowsPlus(o: StructureViewTreeElement) = false
		override fun isAlwaysLeaf(o: StructureViewTreeElement) = false
		override fun shouldEnterElement(o: Any?) = true
	}

	private class CovStructureElement(o: PsiElement) :
			PsiTreeElementBase<PsiElement>(o),
			SortableTreeElement,
			LocationPresentation {
		override fun getIcon(open: Boolean) = element.let { o ->
			when (o) {
				is CovFile -> CovIcons.COV_ICON
				is CovFunctionDeclaration -> CovIcons.FUNCTION_ICON
				is CovStructDeclaration -> CovIcons.STRUCT_ICON
				is CovVariableDeclaration -> CovIcons.VARIABLE_ICON
				is CovNamespaceDeclaration -> CovIcons.NAMESPACE_ICON
				is CovTryCatchStatement -> CovIcons.TRY_CATCH_ICON
				is CovBlockStatement -> CovIcons.BLOCK_ICON
				is CovSwitchStatement -> CovIcons.SWITCH_ICON
				is CovCollapsedStatement -> CovIcons.COLLAPSED_ICON
				is CovIfStatement,
				is CovForStatement,
				is CovLoopUntilStatement,
				is CovWhileStatement -> CovIcons.CONTROL_FLOW_ICON
				else -> CovIcons.COV_BIG_ICON
			}
		}

		override fun getAlphaSortKey() = presentableText
		override fun getPresentableText() = cutText(element.let { o ->
			when (o) {
				is CovFile -> o.name
				is CovFunctionDeclaration -> "${o.nameIdentifier?.text}()"
				is PsiNameIdentifierOwner -> o.nameIdentifier?.text.orEmpty()
				is CovForStatement -> "for ${o.symbol?.text} ${o.forIterate?.run { "iterate ${expr.text}" } ?: "to"}"
				is CovLoopUntilStatement -> "loop${o.expr?.run { " until $text" } ?: ""}"
				is CovWhileStatement -> "while ${o.expr?.text}"
				is CovTryCatchStatement -> "try catch ${o.symbol?.text}"
				is CovSwitchStatement -> "switch statement"
				is CovCollapsedStatement -> "collapsed block"
				is CovBlockStatement -> "begin block"
				is CovIfStatement -> "if ${o.expr?.text}"
				else -> "??"
			}
		}, LONG_TEXT_MAX)

		override fun getLocationString() = ""
		override fun getLocationPrefix() = ""
		override fun getLocationSuffix() = ""
		override fun getChildrenBase() = element.let { o ->
			when (o) {
				is CovFile -> o.children.mapNotNull { (it as? CovStatement)?.inside }
				is CovFunctionDeclaration -> o.bodyOfSomething?.statementList.orEmpty().mapNotNull { it.inside }
				is CovStructDeclaration -> o.children.filter { it is CovFunctionDeclaration || it is CovVariableDeclaration }
				is CovNamespaceDeclaration -> o.bodyOfSomething?.statementList.orEmpty().mapNotNull { it.inside }
				is CovForStatement -> o.generalBody?.bodyOfSomething?.statementList.orEmpty().mapNotNull { it.inside }
				is CovLoopUntilStatement -> o.bodyOfSomething?.statementList.orEmpty().mapNotNull { it.inside }
				is CovWhileStatement -> o.bodyOfSomething?.statementList.orEmpty().mapNotNull { it.inside }
				is CovTryCatchStatement -> o.bodyOfSomethingList.flatMap { it.statementList.mapNotNull { it.inside } }
				is CovSwitchStatement -> o.bodyOfSomethingList.flatMap { it.statementList.mapNotNull { it.inside } }
				is CovBlockStatement -> o.bodyOfSomething?.statementList.orEmpty().mapNotNull { it?.inside }
				is CovIfStatement -> o.bodyOfSomethingList.flatMap { it.statementList.mapNotNull { it.inside } }
				is PsiElement -> o.children.mapNotNull { (it as? CovStatement)?.inside }
				else -> emptyList()
			}.map(::CovStructureElement)
		}
	}
}
