import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

tasks.register("writeResolvedDependencyInventory") {
    description = "Writes the resolved runtime and test dependency graph for the license audit."
    group = "verification"

    doLast {
        val graphConfigurations = mapOf(
            ":app" to setOf(
                "standardReleaseRuntimeClasspath",
                "standardReleaseUnitTestRuntimeClasspath",
                "standardReleaseAndroidTestRuntimeClasspath",
                "fdroidReleaseRuntimeClasspath",
                "fdroidReleaseUnitTestRuntimeClasspath",
                "fdroidReleaseAndroidTestRuntimeClasspath",
            ),
            ":core" to setOf(
                "releaseRuntimeClasspath",
                "releaseUnitTestRuntimeClasspath",
                "debugAndroidTestRuntimeClasspath",
            ),
            ":tts-onnx" to setOf(
                "releaseRuntimeClasspath",
                "releaseUnitTestRuntimeClasspath",
                "debugAndroidTestRuntimeClasspath",
            ),
            ":document-epub" to setOf(
                "releaseRuntimeClasspath",
                "releaseUnitTestRuntimeClasspath",
                "debugAndroidTestRuntimeClasspath",
            ),
            ":playback-export" to setOf(
                "releaseRuntimeClasspath",
                "releaseUnitTestRuntimeClasspath",
                "debugAndroidTestRuntimeClasspath",
            ),
        )
        val selected = subprojects.flatMap { project ->
            project.configurations
                .filter { configuration ->
                    configuration.name in graphConfigurations[project.path].orEmpty()
                }
                .mapNotNull { configuration ->
                    val components = sortedSetOf<String>()
                    val visited = java.util.Collections.newSetFromMap(
                        java.util.IdentityHashMap<ResolvedComponentResult, Boolean>(),
                    )
                    fun visit(result: ResolvedComponentResult) {
                        if (!visited.add(result)) return
                        result.dependencies.forEach { dependency ->
                            val selected = (dependency as? ResolvedDependencyResult)?.selected ?: return@forEach
                            (selected.id as? ModuleComponentIdentifier)?.let { component ->
                                components += "${component.group}:${component.module}:${component.version}"
                            }
                            visit(selected)
                        }
                    }
                    visit(configuration.incoming.resolutionResult.root)
                    if (components.isEmpty()) null else {
                        Triple(
                            "${project.path}:${configuration.name}",
                            if (configuration.name.contains("Test")) "test" else "runtime",
                            components,
                        )
                    }
                }
        }.sortedBy { it.first }

        val output = layout.buildDirectory.file(
            "reports/dependency-audit/resolved-dependencies.json",
        ).get().asFile
        output.parentFile.mkdirs()

        fun json(value: String): String = buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

        val coordinates = selected
            .flatMap { (configuration, scope, components) ->
                components.map { it to (scope to configuration) }
            }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
        output.writeText(buildString {
            appendLine("{")
            appendLine("  \"schema\": \"citac-knjiga-resolved-dependency-graph\",")
            appendLine("  \"version\": 1,")
            appendLine("  \"configurations\": [")
            selected.forEachIndexed { index, (name, scope, _) ->
                append("    {\"name\": ").append(json(name))
                    .append(", \"scope\": ").append(json(scope)).append('}')
                if (index != selected.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ],")
            appendLine("  \"components\": [")
            val coordinateEntries = coordinates.entries.toList()
            coordinateEntries.forEachIndexed { index, (coordinate, records) ->
                val (group, name, version) = coordinate.split(':', limit = 3)
                append("    {\"coordinate\": ").append(json(coordinate))
                    .append(", \"group\": ").append(json(group))
                    .append(", \"name\": ").append(json(name))
                    .append(", \"version\": ").append(json(version))
                    .append(", \"scopes\": [")
                records.map { it.first }.toSortedSet().forEachIndexed { scopeIndex, scope ->
                    if (scopeIndex > 0) append(", ")
                    append(json(scope))
                }
                append("], \"configurations\": [")
                records.map { it.second }.toSortedSet().forEachIndexed { configurationIndex, configuration ->
                    if (configurationIndex > 0) append(", ")
                    append(json(configuration))
                }
                append("]}")
                if (index != coordinateEntries.lastIndex) append(',')
                appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        })
        println("resolved dependency graph: ${output.relativeTo(rootProject.layout.projectDirectory.asFile)}")
    }
}
