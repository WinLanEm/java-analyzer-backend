package actions;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.GraphDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuildCfgAction {

    public Map<String, GraphDTO> execute(String code) {
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

        CompilationUnit compilationUnit = parseResult.getResult()
                .orElseThrow(() -> new CodeParsingException("Unable to parse Java code."));

        List<MethodDeclaration> methods = compilationUnit.findAll(MethodDeclaration.class);
        Map<String, ClassOrInterfaceDeclaration> classIndex = indexClasses(compilationUnit);
        Map<String, Map<String, Object>> fieldIndex = indexFields(classIndex);
        Map<String, String> methodIndex = indexMethods(methods);
        Map<String, GraphDTO> graphs = new LinkedHashMap<>();

        for (MethodDeclaration method : methods) {
            method.getBody().ifPresent(body -> {
                String methodSignature = methodSignature(method);
                String className = className(method);
                MethodGraphBuilder builder = new MethodGraphBuilder(
                        methodIndex,
                        className,
                        method.getNameAsString(),
                        fieldIndex.getOrDefault(className, Map.of())
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

    private static final class MethodGraphBuilder {
        private final Map<String, String> methodIndex;
        private final String className;
        private final String methodName;
        private final Map<String, Object> classFields;
        private final List<AnalyzerNode> nodes = new ArrayList<>();
        private final List<AnalyzerEdge> edges = new ArrayList<>();
        private final Deque<LoopContext> loopContexts = new ArrayDeque<>();
        private int nodeSequence = 1;
        private int edgeSequence = 1;
        private AnalyzerNode exceptionExitNode;

        private MethodGraphBuilder(
                Map<String, String> methodIndex,
                String className,
                String methodName,
                Map<String, Object> classFields
        ) {
            this.methodIndex = methodIndex;
            this.className = className;
            this.methodName = methodName;
            this.classFields = classFields;
        }

        private GraphDTO build(MethodDeclaration method, BlockStmt body) {
            buildBlock(body, List.of());
            List<String> parameters = method.getParameters().stream()
                    .map(parameter -> parameter.getNameAsString())
                    .toList();
            return new GraphDTO(
                    List.copyOf(nodes),
                    List.copyOf(edges),
                    parameters,
                    className,
                    methodName,
                    Collections.unmodifiableMap(new LinkedHashMap<>(classFields))
            );
        }

        private List<FlowTail> buildBlock(BlockStmt blockStmt, List<FlowTail> incomingTails) {
            List<FlowTail> currentTails = incomingTails;

            for (Statement statement : blockStmt.getStatements()) {
                currentTails = buildStatement(statement, currentTails);
            }

            return currentTails;
        }

        private List<FlowTail> buildStatement(Statement statement, List<FlowTail> incomingTails) {
            if (statement.isBlockStmt()) {
                return buildBlock(statement.asBlockStmt(), incomingTails);
            }
            if (statement.isIfStmt()) {
                return buildIfStatement(statement.asIfStmt(), incomingTails);
            }
            if (statement.isWhileStmt()) {
                return buildWhileStatement(statement.asWhileStmt(), incomingTails);
            }
            if (statement.isForStmt()) {
                return buildForStatement(statement.asForStmt(), incomingTails);
            }
            if (statement.isBreakStmt()) {
                return buildBreakStatement(statement.asBreakStmt(), incomingTails);
            }
            if (statement.isContinueStmt()) {
                return buildContinueStatement(statement.asContinueStmt(), incomingTails);
            }
            if (statement.isThrowStmt()) {
                return buildThrowStatement(statement.asThrowStmt(), incomingTails);
            }
            if (statement.isReturnStmt()) {
                return buildReturnStatement(statement.asReturnStmt(), incomingTails);
            }
            if (statement.isExpressionStmt()) {
                return buildExpressionStatement(statement.asExpressionStmt(), incomingTails);
            }

            return incomingTails;
        }

        private List<FlowTail> buildIfStatement(IfStmt ifStmt, List<FlowTail> incomingTails) {
            AnalyzerNode conditionNode = createNode("condition", ifStmt.getCondition().toString(), ifStmt);
            nodes.add(conditionNode);
            connectTails(incomingTails, conditionNode.id());

            List<FlowTail> trueBranchTails = buildStatement(
                    ifStmt.getThenStmt(),
                    List.of(new FlowTail(conditionNode.id(), "true"))
            );

            List<FlowTail> falseBranchTails = ifStmt.getElseStmt()
                    .map(elseStmt -> buildStatement(elseStmt, List.of(new FlowTail(conditionNode.id(), "false"))))
                    .orElseGet(() -> List.of(new FlowTail(conditionNode.id(), "false")));

            List<FlowTail> mergedTails = new ArrayList<>();
            mergedTails.addAll(trueBranchTails);
            mergedTails.addAll(falseBranchTails);
            return List.copyOf(mergedTails);
        }

        private List<FlowTail> buildWhileStatement(WhileStmt whileStmt, List<FlowTail> incomingTails) {
            AnalyzerNode conditionNode = createNode("condition", whileStmt.getCondition().toString(), whileStmt);
            nodes.add(conditionNode);
            connectTails(incomingTails, conditionNode.id());

            LoopContext loopContext = new LoopContext(conditionNode.id());
            loopContexts.push(loopContext);

            List<FlowTail> bodyTails = buildStatement(
                    whileStmt.getBody(),
                    List.of(new FlowTail(conditionNode.id(), "true"))
            );
            loopContexts.pop();
            connectBackEdges(bodyTails, conditionNode.id());

            return loopExits(conditionNode.id(), loopContext);
        }

        private List<FlowTail> buildForStatement(ForStmt forStmt, List<FlowTail> incomingTails) {
            List<FlowTail> currentTails = incomingTails;

            for (Expression initialization : forStmt.getInitialization()) {
                currentTails = buildActionNode(initialization.toString(), initialization, currentTails);
            }

            AnalyzerNode conditionNode = createNode(
                    "condition",
                    forStmt.getCompare().map(Expression::toString).orElse("true"),
                    forStmt
            );
            nodes.add(conditionNode);
            connectTails(currentTails, conditionNode.id());

            List<AnalyzerNode> updateNodes = createUpdateNodes(forStmt);
            String continueTargetNodeId = updateNodes.isEmpty() ? conditionNode.id() : updateNodes.getFirst().id();
            LoopContext loopContext = new LoopContext(continueTargetNodeId);
            loopContexts.push(loopContext);

            List<FlowTail> bodyTails = buildStatement(
                    forStmt.getBody(),
                    List.of(new FlowTail(conditionNode.id(), "true"))
            );
            loopContexts.pop();

            if (updateNodes.isEmpty()) {
                connectBackEdges(bodyTails, conditionNode.id());
            } else if (!bodyTails.isEmpty() || loopContext.hasContinue()) {
                List<FlowTail> updateTails = buildPrecreatedActionNodes(updateNodes, bodyTails);
                connectBackEdges(updateTails, conditionNode.id());
            }

            return loopExits(conditionNode.id(), loopContext);
        }

        private List<FlowTail> buildBreakStatement(BreakStmt breakStmt, List<FlowTail> incomingTails) {
            AnalyzerNode breakNode = createNode("action", "break", breakStmt);
            nodes.add(breakNode);
            connectTails(incomingTails, breakNode.id());

            if (loopContexts.isEmpty()) {
                return List.of(new FlowTail(breakNode.id(), null));
            }

            loopContexts.peek().breakTails().add(new FlowTail(breakNode.id(), "break"));
            return List.of();
        }

        private List<FlowTail> buildContinueStatement(ContinueStmt continueStmt, List<FlowTail> incomingTails) {
            AnalyzerNode continueNode = createNode("action", "continue", continueStmt);
            nodes.add(continueNode);
            connectTails(incomingTails, continueNode.id());

            if (loopContexts.isEmpty()) {
                return List.of(new FlowTail(continueNode.id(), null));
            }

            LoopContext loopContext = loopContexts.peek();
            loopContext.registerContinue();
            connectBackEdges(List.of(new FlowTail(continueNode.id(), "continue")), loopContext.continueTargetNodeId());
            return List.of();
        }

        private List<FlowTail> buildThrowStatement(ThrowStmt throwStmt, List<FlowTail> incomingTails) {
            AnalyzerNode throwNode = createNode("action", "throw " + throwStmt.getExpression(), throwStmt, true);
            nodes.add(throwNode);
            connectTails(incomingTails, throwNode.id());
            connectEdge(throwNode.id(), exceptionExitNode().id(), "throws", false);
            return List.of();
        }

        private List<FlowTail> buildReturnStatement(ReturnStmt returnStmt, List<FlowTail> incomingTails) {
            String label = returnStmt.getExpression()
                    .map(expression -> "return " + expression)
                    .orElse("return");
            AnalyzerNode returnNode = createNode("action", label, returnStmt);
            nodes.add(returnNode);
            connectTails(incomingTails, returnNode.id());
            return List.of();
        }

        private List<FlowTail> buildExpressionStatement(
                ExpressionStmt expressionStatement,
                List<FlowTail> incomingTails
        ) {
            return buildActionNode(expressionStatement.getExpression().toString(), expressionStatement, incomingTails);
        }

        private List<FlowTail> buildActionNode(String label, Node sourceNode, List<FlowTail> incomingTails) {
            AnalyzerNode node = createActionNode(label, sourceNode);
            nodes.add(node);
            connectTails(incomingTails, node.id());
            return List.of(new FlowTail(node.id(), null));
        }

        private List<AnalyzerNode> createUpdateNodes(ForStmt forStmt) {
            List<AnalyzerNode> updateNodes = new ArrayList<>();
            for (Expression update : forStmt.getUpdate()) {
                updateNodes.add(createActionNode(update.toString(), update));
            }
            return updateNodes;
        }

        private AnalyzerNode createActionNode(String label, Node sourceNode) {
            MethodCallExpr methodCallExpr = sourceNode.findFirst(MethodCallExpr.class).orElse(null);
            if (methodCallExpr == null) {
                return createNode("action", label, sourceNode);
            }

            String methodName = methodCallExpr.getNameAsString();
            String receiver = methodCallExpr.getScope().map(Expression::toString).orElse(null);
            String callTarget = receiver == null ? methodIndex.getOrDefault(methodName, methodName) : null;
            return createNode("action", label, sourceNode, false, true, callTarget, receiver, methodName);
        }

        private List<FlowTail> buildPrecreatedActionNodes(List<AnalyzerNode> actionNodes, List<FlowTail> incomingTails) {
            List<FlowTail> currentTails = incomingTails;

            for (AnalyzerNode actionNode : actionNodes) {
                nodes.add(actionNode);
                connectTails(currentTails, actionNode.id());
                currentTails = List.of(new FlowTail(actionNode.id(), null));
            }

            return currentTails;
        }

        private List<FlowTail> loopExits(String conditionNodeId, LoopContext loopContext) {
            List<FlowTail> exits = new ArrayList<>();
            exits.add(new FlowTail(conditionNodeId, "false"));
            exits.addAll(loopContext.breakTails());
            return List.copyOf(exits);
        }

        private AnalyzerNode createNode(String type, String label, Node sourceNode) {
            return createNode(type, label, sourceNode, false);
        }

        private AnalyzerNode createNode(String type, String label, Node sourceNode, boolean isError) {
            return createNode(type, label, sourceNode, isError, false, null, null, null);
        }

        private AnalyzerNode createNode(
                String type,
                String label,
                Node sourceNode,
                boolean isError,
                boolean isCall,
                String callTarget,
                String callReceiver,
                String callMethodName
        ) {
            return new AnalyzerNode(
                    "node-" + nodeSequence++,
                    type,
                    label,
                    sourceNode.getBegin().map(position -> position.line).orElse(-1),
                    isError,
                    isCall,
                    callTarget,
                    callReceiver,
                    callMethodName
            );
        }

        private AnalyzerNode exceptionExitNode() {
            if (exceptionExitNode == null) {
                exceptionExitNode = new AnalyzerNode(
                        "exit-exception",
                        "action",
                        "EXIT (EXCEPTION)",
                        -1,
                        true,
                        false,
                        null,
                        null,
                        null
                );
                nodes.add(exceptionExitNode);
            }
            return exceptionExitNode;
        }

        private void connectTails(List<FlowTail> tails, String targetNodeId) {
            for (FlowTail tail : tails) {
                connectEdge(tail.nodeId(), targetNodeId, tail.label() == null ? "next" : tail.label(), false);
            }
        }

        private void connectBackEdges(List<FlowTail> tails, String targetNodeId) {
            for (FlowTail tail : tails) {
                connectEdge(tail.nodeId(), targetNodeId, tail.label() == null ? "back" : tail.label(), true);
            }
        }

        private void connectEdge(String sourceNodeId, String targetNodeId, String label, boolean isBackEdge) {
            edges.add(new AnalyzerEdge(
                    "edge-" + edgeSequence++,
                    sourceNodeId,
                    targetNodeId,
                    label,
                    isBackEdge
            ));
        }
    }

    private record FlowTail(String nodeId, String label) {
    }

    private static final class LoopContext {
        private final String continueTargetNodeId;
        private final List<FlowTail> breakTails = new ArrayList<>();
        private boolean hasContinue;

        private LoopContext(String continueTargetNodeId) {
            this.continueTargetNodeId = continueTargetNodeId;
        }

        private String continueTargetNodeId() {
            return continueTargetNodeId;
        }

        private List<FlowTail> breakTails() {
            return breakTails;
        }

        private boolean hasContinue() {
            return hasContinue;
        }

        private void registerContinue() {
            hasContinue = true;
        }
    }
}
