/*
 * Copyright (C) 2017 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.analyzer

import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.PackageCuration
import org.ossreviewtoolkit.model.config.PackageCurationProviderConfiguration
import org.ossreviewtoolkit.utils.common.ConfigurablePluginFactory
import org.ossreviewtoolkit.utils.common.Plugin

/**
 * The extension point for [PackageCurationProvider]s.
 */
interface PackageCurationProviderFactory<CONFIG> : ConfigurablePluginFactory<PackageCurationProvider> {
    companion object {
        val ALL = Plugin.getAll<PackageCurationProviderFactory<*>>()

        /**
         * Return a new provider instance for each [enabled][PackageCurationProviderConfiguration.enabled] provider
         * configuration in [configurations]. The given [configurations] must be ordered highest-priority first while
         * the returned providers are ordered lowest-priority first, which is the order in which the corresponding
         * curations must be applied.
         */
        fun create(configurations: List<PackageCurationProviderConfiguration>) =
            configurations.filter { it.enabled }.map { ALL.getValue(it.type).create(it.config) }.asReversed()
    }

    override fun create(config: Map<String, String>): PackageCurationProvider = create(parseConfig(config))

    /**
     * Create a new [PackageCurationProvider] with [config].
     */
    fun create(config: CONFIG): PackageCurationProvider

    /**
     * Parse the [config] map into an object.
     */
    fun parseConfig(config: Map<String, String>): CONFIG
}

/**
 * A provider for [PackageCuration]s.
 */
fun interface PackageCurationProvider {
    /**
     * Return all available [PackageCuration]s for the provided [pkgIds], associated by the package's [Identifier]. Each
     * list of curations must be non-empty; if no curation is available for a package, the returned map must not contain
     * a key for that package's identifier at all.
     */
    // TODO: Maybe make this a suspend function, then all implementing classes could deal with coroutines more easily.
    fun getCurationsFor(pkgIds: Collection<Identifier>): Map<Identifier, List<PackageCuration>>
}
