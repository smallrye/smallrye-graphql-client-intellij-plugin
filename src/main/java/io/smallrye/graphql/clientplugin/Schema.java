package io.smallrye.graphql.clientplugin;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.lang.java.lexer.JavaLexer;
import com.intellij.pom.java.LanguageLevel;
import graphql.language.DescribedNode;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.NonNullType;
import graphql.language.SDLNamedDefinition;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.errors.SchemaProblem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;

class Schema {
    private static final Logger LOG = Logger.getLogger(Schema.class.getName());

    static final Schema INSTANCE = new Schema();

    private TypeDefinitionRegistry cache;
    private Instant lastChange;
    private Consumer<String> errors;

    public Schema withErrors(Consumer<String> errors) {
        this.errors = errors;
        return this;
    }

    public Set<String> typeNames() {
        return schema()
            .stream()
            .flatMap(schema -> schema.types().values().stream())
            .map(SDLNamedDefinition::getName)
            .collect(toSet());
    }

    public Stream<DefinedFieldOrInputValue> fieldsIn(String typeName) {
        return schema()
            .flatMap(schema -> schema.getType(typeName))
            .stream()
            .flatMap(DefinedFieldOrInputValue::in);
    }

    public Optional<TypeDefinitionRegistry> schema() {
        var activeProject = ProjectUtil.getActiveProject();
        if (activeProject == null) return emptyBecause("no active project");
        var basePath = activeProject.getBasePath();
        if (basePath == null) return emptyBecause("no base path in project " + activeProject.getName());
        var path = Paths.get(basePath).resolve("schema.graphql");
        if (!Files.exists(path)) return emptyBecause("no GraphQL schema found at " + path);

        try {
            return Optional.of(parse(path));
        } catch (SchemaProblem e) {
            return emptyBecause("parsing of schema at " + path + " failed:\n" + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<TypeDefinitionRegistry> emptyBecause(String message) {
        debug(message);
        errors.accept(message);
        return Optional.empty();
    }

    private TypeDefinitionRegistry parse(Path path) throws IOException {
        var modified = Files.getLastModifiedTime(path).toInstant();
        if (cache == null || modified.isAfter(lastChange)) {
            debug("reload schema");
            cache = new SchemaParser().parse(path.toFile());
            lastChange = modified;
        }
        return cache;
    }

    private static void debug(String message) {
        LOG.fine(message);
    }

    public static class DefinedFieldOrInputValue {
        private static Stream<DefinedFieldOrInputValue> in(TypeDefinition<?> typeDefinition) {
            return typeDefinition.getChildren().stream()
                .filter(node -> node instanceof FieldDefinition || node instanceof InputValueDefinition)
                .map(node -> new DefinedFieldOrInputValue(typeDefinition.getName(), node));
        }

        private final String containerType;
        private final String name;
        private final String description;
        private final Type<?> type;
        private final List<InputValueDefinition> inputValueDefinitions;

        public DefinedFieldOrInputValue(String containerType, Node<?> node) {
            this.containerType = containerType;
            this.name = ((NamedNode<?>) node).getName();
            var description = ((DescribedNode<?>) node).getDescription();
            this.description = (description == null) ? "" : description.getContent();
            if (node instanceof FieldDefinition) {
                var field = (FieldDefinition) node;
                this.type = field.getType();
                this.inputValueDefinitions = field.getInputValueDefinitions();
            } else {
                var input = (InputValueDefinition) node;
                this.type = input.getType();
                this.inputValueDefinitions = List.of();
            }
        }

        public String getQueryType() {
            return containerType;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return (description == null) ? "" : description;
        }

        public String javaMethod() {
            return "@" + getQueryType() + " " + toJava(type) + " " + getName() + "(" + parameters() + ");";
        }

        public String javaField() {
            return toJava(type) + " " + getName() + ";";
        }

        private static String toJava(Type<?> type) {
            var prefix = "";
            var suffix = "";
            if (type instanceof NonNullType) {
                prefix += "@NonNull ";
                type = ((NonNullType) type).getType();
            }
            if (type instanceof ListType) {
                prefix += "List<";
                suffix += ">";
                type = ((ListType) type).getType();
            }
            if (type instanceof NonNullType) {
                prefix += "@NonNull ";
                type = ((NonNullType) type).getType();
            }
            var typeName = ((NamedNode<?>) type).getName();
            switch (typeName) {
                case "Int":
                    typeName = "Integer";
                    break;
                case "ID":
                    typeName = "String";
                    prefix = "@Id " + prefix;
                    break;
            }
            return prefix + typeName + suffix;
        }

        private String parameters() {
            return inputValueDefinitions.stream()
                .map(DefinedFieldOrInputValue::parameterDeclaration)
                .collect(Collectors.joining(", "));
        }

        private static String parameterDeclaration(InputValueDefinition param) {
            var annotations = "";
            var paramName = param.getName();
            if (JavaLexer.isKeyword(paramName, LanguageLevel.HIGHEST)) {
                annotations = "@Name(\"" + paramName + "\") ";
                paramName = paramName + "_";
            }
            return annotations + toJava(param.getType()) + " " + paramName;
        }
    }
}
