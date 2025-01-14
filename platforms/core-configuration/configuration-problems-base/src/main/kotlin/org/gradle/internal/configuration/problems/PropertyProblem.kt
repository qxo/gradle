/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.configuration.problems

import org.gradle.internal.DisplayName
import org.gradle.internal.code.UserCodeSource
import org.gradle.internal.problems.failure.Failure
import org.gradle.problems.Location
import kotlin.reflect.KClass


/**
 * A problem that does not necessarily compromise the execution of the build.
 */
data class PropertyProblem(
    val trace: PropertyTrace,
    val message: StructuredMessage,
    val exception: Throwable? = null,
    /**
     * A failure containing stack tracing information.
     * The failure may be synthetic when the cause of the problem was not an exception.
     */
    val stackTracingFailure: Failure? = null,
    val documentationSection: DocumentationSection? = null
)


// TODO:configuration-cache extract interface and move enum back to :configuration-cache
enum class DocumentationSection(val anchor: String) {
    NotYetImplemented("config_cache:not_yet_implemented"),
    NotYetImplementedSourceDependencies("config_cache:not_yet_implemented:source_dependencies"),
    NotYetImplementedJavaSerialization("config_cache:not_yet_implemented:java_serialization"),
    NotYetImplementedTestKitJavaAgent("config_cache:not_yet_implemented:testkit_build_with_java_agent"),
    NotYetImplementedBuildServiceInFingerprint("config_cache:not_yet_implemented:build_services_in_fingerprint"),
    TaskOptOut("config_cache:task_opt_out"),
    RequirementsBuildListeners("config_cache:requirements:build_listeners"),
    RequirementsDisallowedTypes("config_cache:requirements:disallowed_types"),
    RequirementsExternalProcess("config_cache:requirements:external_processes"),
    RequirementsTaskAccess("config_cache:requirements:task_access"),
    RequirementsSysPropEnvVarRead("config_cache:requirements:reading_sys_props_and_env_vars"),
    RequirementsUseProjectDuringExecution("config_cache:requirements:use_project_during_execution")
}


typealias StructuredMessageBuilder = StructuredMessage.Builder.() -> Unit


data class StructuredMessage(val fragments: List<Fragment>) {

    override fun toString(): String = fragments.joinToString(separator = "") { fragment ->
        when (fragment) {
            is Fragment.Text -> fragment.text
            is Fragment.Reference -> "'${fragment.name}'"
        }
    }

    sealed class Fragment {

        data class Text(val text: String) : Fragment()

        data class Reference(val name: String) : Fragment()
    }

    companion object {

        fun forText(text: String) = StructuredMessage(listOf(Fragment.Text(text)))

        fun build(builder: StructuredMessageBuilder) = StructuredMessage(
            Builder().apply(builder).fragments
        )
    }

    class Builder {

        internal
        val fragments = mutableListOf<Fragment>()

        fun text(string: String): Builder = apply {
            fragments.add(Fragment.Text(string))
        }

        fun reference(name: String): Builder = apply {
            fragments.add(Fragment.Reference(name))
        }

        fun reference(type: Class<*>): Builder = apply {
            reference(type.name)
        }

        fun reference(type: KClass<*>): Builder = apply {
            reference(type.qualifiedName!!)
        }

        fun message(message: StructuredMessage): Builder = apply {
            fragments.addAll(message.fragments)
        }

        fun build(): StructuredMessage = StructuredMessage(fragments.toList())
    }
}


/**
 * Subtypes are expected to support [PropertyTrace.equals] and [PropertyTrace.hashCode].
 *
 * Subclasses also must provide custom `toString()` implementations,
 * which should invoke [PropertyTrace.asString].
 */
sealed class PropertyTrace {

    object Unknown : PropertyTrace() {
        override fun toString(): String = asString()
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = 0
    }

    object Gradle : PropertyTrace() {
        override fun toString(): String = asString()
        override fun equals(other: Any?): Boolean = other === this
        override fun hashCode(): Int = 1
    }

    @Suppress("DataClassPrivateConstructor")
    data class BuildLogic private constructor(
        val source: DisplayName,
        val lineNumber: Int? = null
    ) : PropertyTrace() {
        constructor(location: Location) : this(location.sourceShortDisplayName, location.lineNumber)
        constructor(userCodeSource: UserCodeSource) : this(userCodeSource.displayName)
        override fun toString(): String = asString()
    }

    data class BuildLogicClass(
        val name: String
    ) : PropertyTrace() {
        override fun toString(): String = asString()
    }

    data class Task(
        val type: Class<*>,
        val path: String
    ) : PropertyTrace() {
        override fun toString(): String = asString()
    }

    data class Bean(
        val type: Class<*>,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCode: String
            get() = trace.containingUserCode

        override fun toString(): String = asString()
    }

    data class Property(
        val kind: PropertyKind,
        val name: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCode: String
            get() = trace.containingUserCode

        override fun toString(): String = asString()
    }

    data class Project(
        val path: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCode: String
            get() = trace.containingUserCode

        override fun toString(): String = asString()
    }

    data class SystemProperty(
        val name: String,
        val trace: PropertyTrace
    ) : PropertyTrace() {
        override val containingUserCode: String
            get() = trace.containingUserCode

        override fun toString(): String = asString()
    }

    abstract override fun toString(): String

    abstract override fun equals(other: Any?): Boolean

    abstract override fun hashCode(): Int

    /**
     * The shared logic for implementing `toString()` in subclasses.
     */
    protected
    fun asString(): String =
        StringBuilder().apply {
            sequence.forEach {
                appendStringOf(it)
            }
        }.toString()

    /**
     * The user code where the problem occurred. User code should generally be some coarse-grained entity such as a plugin or script.
     */
    open val containingUserCode: String
        get() = StringBuilder().apply {
            appendStringOf(this@PropertyTrace)
        }.toString()

    private
    fun StringBuilder.appendStringOf(trace: PropertyTrace) {
        when (trace) {
            is Gradle -> {
                append("Gradle runtime")
            }

            is Property -> {
                append(trace.kind)
                append(" ")
                quoted(trace.name)
                append(" of ")
            }

            is SystemProperty -> {
                append("system property ")
                quoted(trace.name)
                append(" set at ")
            }

            is Bean -> {
                quoted(trace.type.name)
                append(" bean found in ")
            }

            is Task -> {
                append("task ")
                quoted(trace.path)
                append(" of type ")
                quoted(trace.type.name)
            }
            is BuildLogic -> {
                append(trace.source.displayName)
                trace.lineNumber?.let {
                    append(": line ")
                    append(it)
                }
            }

            is BuildLogicClass -> {
                append("class ")
                quoted(trace.name)
            }

            is Unknown -> {
                append("unknown location")
            }

            is Project -> {
                append("project ")
                quoted(trace.path)
                append(" in ")
            }
        }
    }

    private
    fun StringBuilder.quoted(s: String) {
        append('`')
        append(s)
        append('`')
    }

    val sequence: Sequence<PropertyTrace>
        get() = sequence {
            var trace = this@PropertyTrace
            while (true) {
                yield(trace)
                trace = trace.tail ?: break
            }
        }

    private
    val tail: PropertyTrace?
        get() = when (this) {
            is Bean -> trace
            is Property -> trace
            is SystemProperty -> trace
            is Project -> trace
            else -> null
        }
}


enum class PropertyKind {
    Field {
        override fun toString() = "field"
    },
    PropertyUsage {
        override fun toString() = "property usage"
    },
    InputProperty {
        override fun toString() = "input property"
    },
    OutputProperty {
        override fun toString() = "output property"
    }
}
