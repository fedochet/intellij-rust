/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rustSlowTests

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.UIUtil
import org.rust.cargo.RustWithToolchainTestBase
import org.rust.openapiext.fullyRefreshDirectory

abstract class RsRealProjectTestBase : RustWithToolchainTestBase() {
    protected fun openRealProject(info: RealProjectInfo): VirtualFile? {
        val base = openRealProject("testData/${info.path}")
        if (base == null) {
            val name = info.name
            println("SKIP $name: git clone ${info.gitUrl} testData/$name")
            return null
        }
        return base
    }

    private fun openRealProject(path: String): VirtualFile? {
        val projectDir = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            ?: return null
        runWriteAction {
            fullyRefreshDirectoryInUnitTests(projectDir)
            VfsUtil.copyDirectory(
                this,
                projectDir,
                cargoProjectDirectory,
                { !it.name.startsWith(".") && it.name !in EXCLUDED_FILE_NAMES }
            )
            fullyRefreshDirectoryInUnitTests(cargoProjectDirectory)
        }

        refreshWorkspace()
        UIUtil.dispatchAllInvocationEvents()
        return cargoProjectDirectory
    }

    class RealProjectInfo(
        val name: String,
        val path: String,
        val gitUrl: String
    )

    companion object {
        val RUSTC = RealProjectInfo("rust", "rust/src", "https://github.com/rust-lang/rust")
        val CARGO = RealProjectInfo("cargo", "cargo", "https://github.com/rust-lang/cargo")
        val MYSQL_ASYNC = RealProjectInfo("mysql_async", "mysql_async", "https://github.com/blackbeam/mysql_async")
        val TOKIO = RealProjectInfo("tokio", "tokio", "https://github.com/tokio-rs/tokio")

        private val EXCLUDED_FILE_NAMES = setOf("target", "build", "test")
    }
}

val VirtualFile.descendants: List<VirtualFile>
    get() {
        val list = mutableListOf<VirtualFile>()
        fun go(file: VirtualFile): Unit =
            if (file.isDirectory) file.children.forEach(::go) else list += file
        children.forEach(::go)
        return list
    }

fun fullyRefreshDirectoryInUnitTests(directory: VirtualFile) {
    // It's very weird, but real refresh occurs only if
    // we touch file names. At least in test environment
    directory.descendants.forEach { it.name }
    fullyRefreshDirectory(directory)
}
