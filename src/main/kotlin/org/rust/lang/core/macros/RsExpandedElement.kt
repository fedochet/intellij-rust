/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.macros

import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import org.rust.lang.core.psi.RsFile
import org.rust.lang.core.psi.RsMacroCall
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.ancestors
import org.rust.lang.core.psi.ext.stubParent
import org.rust.lang.core.stubs.index.RsIncludeMacroIndex

/**
 *  [RsExpandedElement]s are those elements which exist in temporary,
 *  in-memory PSI-files and are injected into real PSI. Their real
 *  parent is this temp PSI-file, but they are seen by the rest of
 *  the plugin as the children of [getContext] element.
 */
interface RsExpandedElement : RsElement {
    override fun getContext(): PsiElement?

    companion object {
        fun getContextImpl(psi: RsExpandedElement): PsiElement? {
            psi.expandedFrom?.let { return it.context }
            psi.getUserData(RS_EXPANSION_CONTEXT)?.let { return it }
            val parent = psi.stubParent
            if (parent is RsFile) {
                RsIncludeMacroIndex.getIncludingMod(parent)?.let { return it }
            }
            return parent
        }
    }
}

fun RsExpandedElement.setContext(context: RsElement) {
    putUserData(RS_EXPANSION_CONTEXT, context)
}

/**
 * The [RsMacroCall] that directly expanded to this element or
 * null if this element is not directly produced by a macro
 */
val RsExpandedElement.expandedFrom: RsMacroCall?
    get() = project.macroExpansionManager.getExpandedFrom(this)

val RsExpandedElement.expandedFromRecursively: RsMacroCall?
    get() {
        var call: RsMacroCall = expandedFrom ?: return null
        while (true) {
            call = call.expandedFrom ?: break
        }

        return call
    }

fun PsiElement.findMacroCallExpandedFrom(): RsMacroCall? {
    val found = ancestors
        .filterIsInstance<RsExpandedElement>()
        .mapNotNull { it.expandedFromRecursively }
        .firstOrNull()
    return found?.findMacroCallExpandedFrom() ?: found
}


private val RS_EXPANSION_CONTEXT = Key.create<RsElement>("org.rust.lang.core.psi.CODE_FRAGMENT_FILE")

