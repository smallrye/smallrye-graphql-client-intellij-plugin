package io.smallrye.graphql.clientplugin

import com.intellij.ide.impl.ProjectUtil
import com.intellij.lang.java.lexer.JavaLexer
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationType.ERROR
import com.intellij.notification.NotificationType.WARNING
import com.intellij.notification.Notifications
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.pom.java.LanguageLevel
import graphql.language.DescribedNode
import graphql.language.FieldDefinition
import graphql.language.InputValueDefinition
import graphql.language.ListType
import graphql.language.NamedNode
import graphql.language.Node
import graphql.language.NonNullType
import graphql.language.Type
import graphql.language.TypeDefinition
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.TypeDefinitionRegistry
import io.ktor.util.collections.ConcurrentSet
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.util.logging.Logger
import java.util.stream.Collectors
import java.util.stream.Stream

internal class Schema {
    private var cache: TypeDefinitionRegistry? = null
    private var lastChange: Instant? = null

    fun typeNames(): Set<String> {
        return schema()
            ?.types()
            ?.values
            ?.map { obj -> obj.name }
            ?.toSet()
            ?: emptySet()
    }

    fun fieldsIn(typeName: String): Stream<DefinedFieldOrInputValue> {
        return schema()
            ?.getType(typeName)
            ?.stream()
            ?.flatMap { typeDefinition -> DefinedFieldOrInputValue.of(typeDefinition) }
            ?: Stream.empty()
    }

    private fun schema(): TypeDefinitionRegistry? {
        val activeProject = ProjectUtil.getActiveProject() ?: return noActiveProject()
        val basePath = activeProject.basePath ?: return noBasePathInProject(activeProject)
        val path = Paths.get(basePath).resolve("schema.graphql")
        return if (!Files.exists(path)) noGraphQLSchemaFound(path)
        else try {
            parse(path)
        } catch (e: RuntimeException) {
            schemaParsingFailed(path, e)
        }
    }

    private fun noActiveProject(): TypeDefinitionRegistry? {
        notify(ERROR, "no active project")
        return null
    }

    private fun noBasePathInProject(activeProject: Project): TypeDefinitionRegistry? {
        notify(ERROR, "no base path in project " + activeProject.name)
        return null
    }

    private fun schemaParsingFailed(path: Path?, e: RuntimeException): TypeDefinitionRegistry? {
        notify(ERROR, "parsing of schema at $path failed:\n${e.message}")
        return null
    }

    private fun parse(path: Path): TypeDefinitionRegistry {
        val modified = Files.getLastModifiedTime(path).toInstant()
        if (cache == null || modified.isAfter(lastChange)) {
            debug("reload schema")
            cache = SchemaParser().parse(path.toFile())
            lastChange = modified
            SHOWN_NOTIFICATIONS.clear()
        }
        return cache!!
    }

    private fun noGraphQLSchemaFound(path: Path): TypeDefinitionRegistry? {
        notify(WARNING, "no GraphQL schema found at $path")
        return null
    }

    private fun notify(notificationType: NotificationType, message: String) {
        debug(message)
        if (SHOWN_NOTIFICATIONS.add(message)) {
            Notifications.Bus.notify(Notification("GraphQL Client Notifications", message, notificationType))
        }
    }

    internal class DefinedFieldOrInputValue(val queryType: String, node: Node<*>) {
        val name: String
        val description: String
        private var type: Type<*>
        private var inputValueDefinitions: List<InputValueDefinition>

        init {
            name = (node as NamedNode<*>).name
            val description = (node as DescribedNode<*>).description
            this.description = if (description == null) "" else description.getContent()
            if (node is FieldDefinition) {
                type = node.type
                inputValueDefinitions = node.inputValueDefinitions
            } else {
                val input = node as InputValueDefinition
                type = input.type
                inputValueDefinitions = listOf()
            }
        }

        fun javaMethod(): String {
            return "@" + queryType + " " + toJava(type) + " " + name + "(" + parameters() + ");"
        }

        fun javaField(): String {
            return toJava(type) + " " + name + ";"
        }

        private fun parameters(): String {
            return inputValueDefinitions.stream()
                .map { param -> parameterDeclaration(param) }
                .collect(Collectors.joining(", "))
        }

        companion object {
            internal fun of(typeDefinition: TypeDefinition<*>): Stream<DefinedFieldOrInputValue> {
                return typeDefinition.children.stream()
                    .filter { node -> node is FieldDefinition || node is InputValueDefinition }
                    .map { node -> DefinedFieldOrInputValue(typeDefinition.name, node) }
            }

            private fun toJava(type: Type<*>): String {
                var result = type
                var prefix = ""
                var suffix = ""
                if (result is NonNullType) {
                    prefix += "@NonNull "
                    result = result.type
                }
                if (result is ListType) {
                    prefix += "List<"
                    suffix += ">"
                    result = result.type
                }
                if (result is NonNullType) {
                    prefix += "@NonNull "
                    result = result.type
                }
                var typeName = (result as NamedNode<*>).name
                when (typeName) {
                    "Int" -> typeName = "Integer"
                    "ID" -> {
                        typeName = "String"
                        prefix = "@Id $prefix"
                    }
                }
                return prefix + typeName + suffix
            }

            private fun parameterDeclaration(param: InputValueDefinition): String {
                var annotations = ""
                var paramName = param.name
                if (JavaLexer.isKeyword(paramName, LanguageLevel.HIGHEST)) {
                    annotations = "@Name(\"$paramName\") "
                    paramName += "_"
                }
                return annotations + toJava(param.type) + " " + paramName
            }
        }
    }

    companion object {
        lateinit var editor: Editor
        val INSTANCE: Schema = Schema()
        private val SHOWN_NOTIFICATIONS = ConcurrentSet<String>()
        private val LOG = Logger.getLogger(Schema::class.java.name)
        private fun debug(message: String) {
            LOG.fine(message)
        }
    }
}
