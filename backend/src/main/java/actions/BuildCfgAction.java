package actions;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import dto.GraphDTO;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildCfgAction {

    public Map<String, GraphDTO> execute(String code) {
        CompilationUnit compilationUnit = parseCode(code);
        ProjectMetadata metadata = indexProjectMetadata(compilationUnit);
        return buildMethodGraphs(metadata);
    }

    private CompilationUnit parseCode(String code) {
        ParseResult<CompilationUnit> parseResult = new JavaParser()
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(code));

        if (!parseResult.isSuccessful()) {
            String message = parseResult.getProblems().stream()
                    .map(problem -> problem.getLocation()
                            .map(location -> problem.getMessage() + " at " + location)
                            .orElse(problem.getMessage()))
                    .reduce((left, right) -> left + System.lineSeparator() + right)
                    .orElse("Unable to parse Java code.");
            throw new CodeParsingException(message);
        }

        return parseResult.getResult()
                .orElseThrow(() -> new CodeParsingException("Unable to parse Java code."));
    }

    private ProjectMetadata indexProjectMetadata(CompilationUnit compilationUnit) {
        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        Map<String, ClassOrInterfaceDeclaration> classIndex = indexClasses(compilationUnit);
        Map<String, Map<String, Object>> fieldIndex = indexFields(classIndex);
        Map<String, String> methodIndex = indexMethods(methods);
        return new ProjectMetadata(methods, classIndex, fieldIndex, methodIndex);
    }

    private Map<String, GraphDTO> buildMethodGraphs(ProjectMetadata metadata) {
        Map<String, GraphDTO> graphs = new LinkedHashMap<>();

        for (MethodDeclaration method : metadata.methods()) {
            method.getBody().ifPresent(body -> {
                String methodSignature = methodSignature(method);
                String className = className(method);
                MethodGraphBuilder builder = new MethodGraphBuilder(
                        metadata.methodIndex(),
                        className,
                        method.getNameAsString(),
                        metadata.fieldIndex().getOrDefault(className, Map.of())
                );
                graphs.put(methodSignature, builder.build(method, body));
            });
        }

        return Map.copyOf(graphs);
    }

    public static final class CodeParsingException extends IllegalArgumentException {
        public CodeParsingException(String message) {
            super(message);
        }
    }

    private Map<String, String> indexMethods(List<MethodDeclaration> methods) {
        Map<String, String> methodIndex = new LinkedHashMap<>();
        for (MethodDeclaration method : methods) {
            methodIndex.putIfAbsent(method.getNameAsString(), methodSignature(method));
        }
        return methodIndex;
    }

    private Map<String, ClassOrInterfaceDeclaration> indexClasses(CompilationUnit compilationUnit) {
        Map<String, ClassOrInterfaceDeclaration> classIndex = new LinkedHashMap<>();
        for (ClassOrInterfaceDeclaration classDeclaration : compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            classIndex.put(classDeclaration.getNameAsString(), classDeclaration);
        }
        return Map.copyOf(classIndex);
    }

    private Map<String, Map<String, Object>> indexFields(Map<String, ClassOrInterfaceDeclaration> classIndex) {
        Map<String, Map<String, Object>> fieldIndex = new LinkedHashMap<>();
        for (Map.Entry<String, ClassOrInterfaceDeclaration> classEntry : classIndex.entrySet()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (FieldDeclaration fieldDeclaration : classEntry.getValue().getFields()) {
                for (VariableDeclarator variable : fieldDeclaration.getVariables()) {
                    fields.put(variable.getNameAsString(), defaultValue(variable));
                }
            }
            fieldIndex.put(classEntry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
        }
        return Collections.unmodifiableMap(fieldIndex);
    }

    private Object defaultValue(VariableDeclarator variable) {
        if (variable.getInitializer().isPresent()) {
            return literalValue(variable.getInitializer().get());
        }

        String type = variable.getTypeAsString();
        if ("boolean".equals(type)) {
            return false;
        }
        if ("byte".equals(type) || "short".equals(type) || "int".equals(type) || "long".equals(type)
                || "float".equals(type) || "double".equals(type) || "char".equals(type)) {
            return 0;
        }
        return null;
    }

    private Object literalValue(Expression expression) {
        if (expression.isArrayCreationExpr() || expression.isArrayInitializerExpr()) {
            return expression.toString();
        }
        if (expression.isObjectCreationExpr()) {
            return expression.asObjectCreationExpr().toString();
        }
        if (expression.isBooleanLiteralExpr()) {
            return expression.asBooleanLiteralExpr().getValue();
        }
        if (expression.isIntegerLiteralExpr()) {
            return expression.asIntegerLiteralExpr().asInt();
        }
        if (expression.isLongLiteralExpr()) {
            return expression.asLongLiteralExpr().asLong();
        }
        if (expression.isDoubleLiteralExpr()) {
            return expression.asDoubleLiteralExpr().asDouble();
        }
        if (expression.isCharLiteralExpr()) {
            return expression.asCharLiteralExpr().asChar();
        }
        if (expression.isStringLiteralExpr()) {
            return expression.asStringLiteralExpr().asString();
        }
        if (expression.isNullLiteralExpr()) {
            return null;
        }
        return 0;
    }

    private static String methodSignature(MethodDeclaration method) {
        return className(method) + "." + method.getNameAsString();
    }

    private static String className(MethodDeclaration method) {
        return method.findAncestor(ClassOrInterfaceDeclaration.class)
                .map(ClassOrInterfaceDeclaration::getNameAsString)
                .orElse("Global");
    }

    private record ProjectMetadata(
            List<MethodDeclaration> methods,
            Map<String, ClassOrInterfaceDeclaration> classIndex,
            Map<String, Map<String, Object>> fieldIndex,
            Map<String, String> methodIndex
    ) {
    }
}
