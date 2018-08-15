/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.intentions

import com.intellij.execution.process.ProcessOutput
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import org.rust.cargo.project.model.CargoProject
import org.rust.cargo.project.settings.rustSettings
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.PackageOrigin
import org.rust.cargo.toolchain.Cargo
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.cargo.toolchain.RustChannel
import org.rust.cargo.toolchain.execute
import org.rust.ide.notifications.showBalloon
import org.rust.lang.core.macros.RsExpandedElement
import org.rust.lang.core.psi.RsPsiFactory
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.STD_DERIVABLE_TRAITS
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.*

data class TemporaryProject(val rootDir: Path, val cargoFile: Path, val main: Path) {

    fun updateContent(manifestText: String, mainText: String) {
        cargoFile.toFile().writeText(manifestText)
        main.toFile().writeText(mainText)
    }

    companion object {

        fun create(projectName: String): TemporaryProject {
            val projectDir = createTempDir(prefix = "${projectName}_macro_expansion_project")
            val cargoFile = File(projectDir, "Cargo.toml")
            val main = File(projectDir.resolveMakingDirs("src"), "main.rs")

            return TemporaryProject(projectDir.toPath(), cargoFile.toPath(), main.toPath())
        }
    }

}

interface CustomDeriveExpander {

    fun expandMacro(project: Project, ctx: RsOuterAttributeOwner): List<RsExpandedElement>?

    companion object {
        fun getInstance(project: Project): CustomDeriveExpander =
            ServiceManager.getService(project, CustomDeriveExpander::class.java)
    }

}

class CustomDeriveExpanderImpl : CustomDeriveExpander {

    private val temporaryProjects = WeakHashMap<CargoProject, TemporaryProject>()

    override fun expandMacro(project: Project, ctx: RsOuterAttributeOwner): List<RsExpandedElement>? {
        val cargoProject = ctx.cargoProject ?: return null
        val packages = ctx.cargoWorkspace?.packages ?: return null
        val cargo = project.rustSettings.toolchain?.rawCargo() ?: return null

        val packagesFromManifest = packages.filter { it.isDeclaredInManifest && it.isOuterDependency }

        val currentProject = temporaryProjects
            .getOrPut(cargoProject) { TemporaryProject.create(cargoProject.presentableName) }

        val manifestText = createCargoManifest(packagesFromManifest)
        val mainText = buildString {
            packagesFromManifest.joinTo(this, "\n") { it.procMacroImportFormat }
            appendln(ctx.text)
        }

        project.showBalloon("Currently ${temporaryProjects.size} projects in cache!", NotificationType.INFORMATION)

        currentProject.updateContent(manifestText, mainText)

        val stdoutLines = performMacroExpansion(currentProject, cargo)?.stdoutLines ?: return null

        val expansionText = stdoutLines.joinToString("\n")
        val expansionElements = RsPsiFactory(project).createFile(expansionText).childrenOfType<RsExpandedElement>()

        return expansionElements
    }

    private fun performMacroExpansion(currentProject: TemporaryProject, cargo: Cargo): ProcessOutput? {
        val cargoCommandLine = CargoCommandLine(
                "rustc",
                currentProject.rootDir,
                channel = RustChannel.NIGHTLY,
                additionalArguments = listOf("-q", "--", "-Zunstable-options", "--pretty", "expanded")
        )

        return cargo.toGeneralCommandLine(cargoCommandLine).execute(timeoutInMilliseconds = -1)
    }

}

private fun createCargoManifest(packages: List<CargoWorkspace.Package>): String {

    val dependencies = formatDependencies(packages)

    return buildString {
        appendln("[package]")
        appendln("name = \"hello-world\"")
        appendln("version = \"0.1.0\"")
        appendln("authors = []")
        appendln("publish = false")

        appendln("[dependencies]")
        append(dependencies)
    }

}

private fun formatDependencies(packages: List<CargoWorkspace.Package>): String {
    return packages.joinToString(separator = "\n") { it.cargoDependencyFormat }
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
