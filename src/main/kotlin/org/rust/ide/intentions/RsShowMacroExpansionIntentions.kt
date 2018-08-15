/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.google.common.annotations.VisibleForTesting
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.ide.actions.macroExpansion.*
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.RsOuterAttr
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsOuterAttributeOwner
import org.rust.lang.core.psi.ext.ancestorOrSelf
import org.rust.lang.core.psi.ext.ancestorStrict
import org.rust.lang.core.psi.ext.name
import org.rust.lang.core.resolve.STD_DERIVABLE_TRAITS
import java.io.File
import java.io.IOException

sealed class ExpandableElement {
    data class MacroCall(val macroCall: RsMacroCall) : ExpandableElement()
    data class CustomDerive(val customDerive: RsStructItem) : ExpandableElement()
}

abstract class RsShowMacroExpansionIntentionBase(private val expandRecursively: Boolean) :
    RsElementBaseIntentionAction<ExpandableElement>() {

    override fun getFamilyName() = text

    override fun findApplicableContext(project: Project, editor: Editor, element: PsiElement): ExpandableElement? {
        val macroCall = element.ancestorOrSelf<RsMacroCall>()

        if (macroCall != null) {
            return ExpandableElement.MacroCall(macroCall)
        }

        val deriveAttr = element.ancestorOrSelf<RsOuterAttr>()?.takeIf { it.metaItem.name == "derive" }

        return deriveAttr
            ?.ancestorStrict<RsStructItem>()
            ?.let { ExpandableElement.CustomDerive(it) }
    }

    override fun invoke(project: Project, editor: Editor, ctx: ExpandableElement) {
        when (ctx) {
            is ExpandableElement.MacroCall -> {
                val expansionDetails = expandMacroForViewWithProgress(project, ctx.macroCall, expandRecursively)
                showExpansion(project, editor, expansionDetails)
            }

            is ExpandableElement.CustomDerive -> {
                val expansions = project.computeWithCancelableProgress("Proc macro expansion") {
                    runReadAction {
                        CustomDeriveExpander.getInstance(project)
                            .expandMacro(project, ctx.customDerive)
                            .orEmpty()
                    }
                }

                showExpansion(project, editor, MacroExpansionViewDetails("struct", expansions))
            }
        }
    }

    /** Progress window cannot be shown in the write action, so it have to be disabled. **/
    override fun startInWriteAction(): Boolean = false

    /**
     * This method is required for testing to avoid actually creating popup and editor.
     * Inspired by [com.intellij.codeInsight.hint.actions.ShowImplementationsAction].
     */
    @VisibleForTesting
    protected open fun showExpansion(project: Project, editor: Editor, expansionDetails: MacroExpansionViewDetails) {
        showMacroExpansionPopup(project, editor, expansionDetails)
    }

}

class RsShowRecursiveMacroExpansionIntention : RsShowMacroExpansionIntentionBase(expandRecursively = true) {
    override fun getText() = "Show recursive macro expansion"
}

class RsShowSingleStepMacroExpansionIntention : RsShowMacroExpansionIntentionBase(expandRecursively = false) {
    override fun getText() = "Show single step macro expansion"
}

private fun RsOuterAttributeOwner.hasCustomDerive(): Boolean {
    val deriveAttributes = outerAttrList
        .map { it.metaItem }
        .filter { it.name == "derive" }

    val derivedClasses = deriveAttributes
        .flatMap { it.metaItemArgs?.metaItemList.orEmpty() }
        .map { it.name }

    return derivedClasses.any { it !in STD_DERIVABLE_TRAITS }
}

/**
 * Checks if this package is taken from somewhere (for example, from cargo github index), and not a submodule of the
 * root project.
 */
private val CargoWorkspace.Package.isDeclaredInManifest
    get() = origin == PackageOrigin.DEPENDENCY

private val CargoWorkspace.Package.isOuterDependency
    get() = source != null

private val CargoWorkspace.Package.cargoDependencyFormat: String
    get() = "$name = \"$version\""

private val CargoWorkspace.Package.procMacroImportFormat: String
    get() = """
        #[macro_use]
        extern crate $normName;
    """.trimIndent()

private fun CargoWorkspace.Package.hasProcMacroTargets() =
    targets.any { it.hasSingleProcMacroType() }

private fun CargoWorkspace.Target.hasSingleProcMacroType() =
    crateTypes.singleOrNull() === CargoWorkspace.CrateType.PROC_MACRO

private fun File.resolveMakingDirs(name: String): File {
    return resolve(name).apply {
        if (!mkdirs()) throw IOException("Cannot create directories to resolve $name in $this")
    }
}
