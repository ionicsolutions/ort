/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package org.ossreviewtoolkit.plugins.packagemanagers.python

import java.io.File

import org.apache.logging.log4j.kotlin.logger

import org.ossreviewtoolkit.analyzer.AbstractPackageManagerFactory
import org.ossreviewtoolkit.analyzer.PackageManager
import org.ossreviewtoolkit.downloader.VersionControlSystem
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.ProjectAnalyzerResult
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.PythonInspector
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.toOrtPackages
import org.ossreviewtoolkit.plugins.packagemanagers.python.utils.toPackageReferences
import org.ossreviewtoolkit.utils.common.CommandLineTool
import org.ossreviewtoolkit.utils.common.ProcessCapture
import org.ossreviewtoolkit.utils.common.withoutPrefix
import org.ossreviewtoolkit.utils.common.withoutSuffix
import org.ossreviewtoolkit.utils.ort.createOrtTempFile

/**
 * [Poetry](https://python-poetry.org/) package manager for Python.
 */
class Poetry(
    name: String,
    analysisRoot: File,
    analyzerConfig: AnalyzerConfiguration,
    repoConfig: RepositoryConfiguration
) : PackageManager(name, analysisRoot, analyzerConfig, repoConfig), CommandLineTool {
    class Factory : AbstractPackageManagerFactory<Poetry>("Poetry") {
        override val globsForDefinitionFiles = listOf("poetry.lock")

        override fun create(
            analysisRoot: File,
            analyzerConfig: AnalyzerConfiguration,
            repoConfig: RepositoryConfiguration
        ) = Poetry(type, analysisRoot, analyzerConfig, repoConfig)
    }

    override fun command(workingDir: File?) = "poetry"

    override fun transformVersion(output: String) = output.substringAfter("version ").removeSuffix(")")

    override fun resolveDependencies(definitionFile: File, labels: Map<String, String>): List<ProjectAnalyzerResult> {
        val scopeName = parseScopeNamesFromPyproject(definitionFile.resolveSibling(Pip.PYPROJECT_FILENAME))
        val resultsForScopeName = scopeName.associateWith { inspectLockfile(definitionFile, it) }

        val packages = resultsForScopeName
            .flatMap { (_, results) -> results.packages }
            .toOrtPackages()
            .distinctBy { it.id }
            .toSet()

        val project = Project.EMPTY.copy(
            id = Identifier(
                type = managerName,
                namespace = "",
                name = definitionFile.relativeTo(analysisRoot).path,
                version = VersionControlSystem.getCloneInfo(definitionFile.parentFile).revision
            ),
            definitionFilePath = VersionControlSystem.getPathInfo(definitionFile).path,
            scopeDependencies = resultsForScopeName.mapTo(mutableSetOf()) { (scopeName, results) ->
                Scope(scopeName, results.resolvedDependenciesGraph.toPackageReferences())
            },
            vcsProcessed = processProjectVcs(definitionFile.parentFile)
        )

        return listOf(ProjectAnalyzerResult(project, packages))
    }

    /**
     * Return the result of running Python inspector against a requirements file generated by exporting the dependencies
     * in [lockfile] with the scope named [dependencyGroupName] via the `poetry export` command.
     */
    private fun inspectLockfile(lockfile: File, dependencyGroupName: String): PythonInspector.Result {
        val workingDir = lockfile.parentFile
        val requirementsFile = createOrtTempFile("requirements.txt")

        logger.info { "Generating '${requirementsFile.name}' file in '$workingDir' directory..." }

        val command = listOfNotNull(
            command(),
            "export",
            "--without-hashes",
            "--format=requirements.txt",
            "--only=$dependencyGroupName"
        )

        val requirements = ProcessCapture(workingDir, *command.toTypedArray()).requireSuccess().stdout
        requirementsFile.writeText(requirements)

        return Pip(managerName, analysisRoot, analyzerConfig, repoConfig).runPythonInspector(requirementsFile).also {
            requirementsFile.delete()
        }
    }
}

internal fun parseScopeNamesFromPyproject(pyprojectFile: File): Set<String> {
    // The implicit "main" scope is always present.
    val scopes = mutableSetOf("main")

    if (!pyprojectFile.isFile) return scopes

    pyprojectFile.readLines().mapNotNullTo(scopes) { line ->
        // Handle both "[tool.poetry.<scope>-dependencies]" and "[tool.poetry.group.<scope>.dependencies]" syntax.
        val poetryEntry = line.withoutPrefix("[tool.poetry.")
        poetryEntry.withoutPrefix("group.") { poetryEntry }
            .withoutSuffix("dependencies]")
            ?.trimEnd('-', '.')
            ?.takeUnless { it.isEmpty() }
    }

    return scopes
}
