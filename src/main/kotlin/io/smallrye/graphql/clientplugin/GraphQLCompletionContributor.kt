package io.smallrye.graphql.clientplugin

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.patterns.PsiJavaPatterns
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiWhiteSpace
import com.intellij.util.ProcessingContext
import io.smallrye.graphql.clientplugin.Schema.DefinedFieldOrInputValue
import java.util.logging.Logger
import java.util.stream.Collectors
import java.util.stream.Stream

class GraphQLCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PsiJavaPatterns.psiElement(), GraphQLCompletionProvider())
    }

    private class GraphQLCompletionProvider : CompletionProvider<CompletionParameters>() {
        private var editor: Editor? = null

        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            this.editor = parameters.editor
            val schema = schema()
            graphQLClientApi(parameters)?.let { api: PsiClass ->
                val existingMethods = existing(api, PsiMethod::class.java)
                Stream.concat(
                    schema.fieldsIn("Query"),
                    schema.fieldsIn("Mutation")
                )
                    .filter { query: DefinedFieldOrInputValue -> query.name !in existingMethods }
                    .forEach { query: DefinedFieldOrInputValue -> addLookupElement(result, query.javaMethod(), query) }
            }
            graphQLType(parameters)?.let { type: PsiClass ->
                val existingFields = existing(type, PsiField::class.java)
                schema.fieldsIn(type.name!!)
                    .filter { field: DefinedFieldOrInputValue -> field.name !in existingFields }
                    .forEach { query: DefinedFieldOrInputValue -> addLookupElement(result, query.javaField(), query) }
            }
        }

        private fun graphQLClientApi(parameters: CompletionParameters): PsiClass? {
            val api = findApi(parameters)
            if (api == null || !api.isInterface || api.getAnnotation("io.smallrye.graphql.client.typesafe.api.GraphQLClientApi") == null)
                return null
            LOG.fine("found GraphQLClientApi " + api.name)
            return api
        }

        private fun graphQLType(parameters: CompletionParameters): PsiClass? {
            val api = findApi(parameters)
            if (api == null || api.isInterface || api.name !in schema().typeNames()) return null
            LOG.fine("found GraphQL type " + api.name)
            return api
        }

        private fun findApi(parameters: CompletionParameters): PsiClass? {
            var element = parameters.originalPosition ?: return null
            // user already typed a GraphQL field/method name
            if (element is PsiIdentifier && element.getParent() is PsiJavaCodeReferenceElement) {
                while (element.parent != null) {
                    if (element is PsiClass) return element
                    element = element.parent
                }
            } else  // user manually started autocomplete at whitespace
                if (element is PsiWhiteSpace &&
                    element.getParent() is PsiClass
                ) return element.getParent() as PsiClass
            return null
        }

        private fun existing(api: PsiClass, type: Class<out PsiNamedElement>): Set<String> {
            return Stream.of(*api.children)
                .filter { obj -> type.isInstance(obj) }
                .map { element -> (element as PsiNamedElement).name!! }
                .collect(Collectors.toSet())
        }

        private fun schema(): Schema {
            return Schema.INSTANCE.withErrors { message: String -> this.error(message) }
        }

        private fun error(message: String) {
            ApplicationManager.getApplication().invokeLater { HintManager.getInstance().showErrorHint(editor!!, message) }
        }

        companion object {
            private val LOG = Logger.getLogger(GraphQLCompletionProvider::class.java.name)
            private fun addLookupElement(result: CompletionResultSet, name: String, field: DefinedFieldOrInputValue) {
                result.addElement(
                    LookupElementBuilder.create(name)
                        .withPresentableText(field.name)
                        .withTailText(" " + field.description)
                        .withTypeText("GraphQL " + field.queryType)
                )
            }
        }
    }
}
