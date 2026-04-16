package actions;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
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
import constants.CfgConstants;
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

final class MethodGraphBuilder {
    private final Map<String, String> methodIndex;
    private final String className;
    private final String methodName;
    private final Map<String, Object> classFields;
    private final Map<Class<? extends Statement>, StatementHandler> statementHandlers;
    private final List<AnalyzerNode> nodes = new ArrayList<>();
    private final List<AnalyzerEdge> edges = new ArrayList<>();
    private final Deque<LoopContext> loopContexts = new ArrayDeque<>();
    private int nodeSequence = 1;
    private int edgeSequence = 1;
    private AnalyzerNode exceptionExitNode;

    MethodGraphBuilder(
            Map<String, String> methodIndex,
            String className,
            String methodName,
            Map<String, Object> classFields
    ) {
        this.methodIndex = methodIndex;
        this.className = className;
        this.methodName = methodName;
        this.classFields = classFields;
        this.statementHandlers = statementHandlers();
    }

    GraphDTO build(MethodDeclaration method, BlockStmt body) {
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

    private Map<Class<? extends Statement>, StatementHandler> statementHandlers() {
        Map<Class<? extends Statement>, StatementHandler> handlers = new LinkedHashMap<>();
        handlers.put(BlockStmt.class, (statement, incomingTails) -> buildBlock(statement.asBlockStmt(), incomingTails));
        handlers.put(IfStmt.class, (statement, incomingTails) -> buildIfStatement(statement.asIfStmt(), incomingTails));
        handlers.put(WhileStmt.class, (statement, incomingTails) -> buildWhileStatement(statement.asWhileStmt(), incomingTails));
        handlers.put(ForStmt.class, (statement, incomingTails) -> buildForStatement(statement.asForStmt(), incomingTails));
        handlers.put(BreakStmt.class, (statement, incomingTails) -> buildBreakStatement(statement.asBreakStmt(), incomingTails));
        handlers.put(ContinueStmt.class, (statement, incomingTails) -> buildContinueStatement(statement.asContinueStmt(), incomingTails));
        handlers.put(ThrowStmt.class, (statement, incomingTails) -> buildThrowStatement(statement.asThrowStmt(), incomingTails));
        handlers.put(ReturnStmt.class, (statement, incomingTails) -> buildReturnStatement(statement.asReturnStmt(), incomingTails));
        handlers.put(ExpressionStmt.class, (statement, incomingTails) -> buildExpressionStatement(statement.asExpressionStmt(), incomingTails));
        return Map.copyOf(handlers);
    }

    private List<FlowTail> buildBlock(BlockStmt blockStmt, List<FlowTail> incomingTails) {
        List<FlowTail> currentTails = incomingTails;

        for (Statement statement : blockStmt.getStatements()) {
            currentTails = buildStatement(statement, currentTails);
        }

        return currentTails;
    }

    private List<FlowTail> buildStatement(Statement statement, List<FlowTail> incomingTails) {
        StatementHandler handler = statementHandlers.get(statement.getClass());
        return handler == null ? incomingTails : handler.handle(statement, incomingTails);
    }

    private List<FlowTail> buildIfStatement(IfStmt ifStmt, List<FlowTail> incomingTails) {
        AnalyzerNode conditionNode = createNode(CfgConstants.NODE_CONDITION, ifStmt.getCondition().toString(), ifStmt);
        nodes.add(conditionNode);
        connectTails(incomingTails, conditionNode.id());

        List<FlowTail> trueBranchTails = buildStatement(
                ifStmt.getThenStmt(),
                List.of(new FlowTail(conditionNode.id(), CfgConstants.EDGE_TRUE))
        );

        List<FlowTail> falseBranchTails = ifStmt.getElseStmt()
                .map(elseStmt -> buildStatement(elseStmt, List.of(new FlowTail(conditionNode.id(), CfgConstants.EDGE_FALSE))))
                .orElseGet(() -> List.of(new FlowTail(conditionNode.id(), CfgConstants.EDGE_FALSE)));

        List<FlowTail> mergedTails = new ArrayList<>();
        mergedTails.addAll(trueBranchTails);
        mergedTails.addAll(falseBranchTails);
        return List.copyOf(mergedTails);
    }

    private List<FlowTail> buildWhileStatement(WhileStmt whileStmt, List<FlowTail> incomingTails) {
        AnalyzerNode conditionNode = createNode(CfgConstants.NODE_CONDITION, whileStmt.getCondition().toString(), whileStmt);
        nodes.add(conditionNode);
        connectTails(incomingTails, conditionNode.id());

        LoopContext loopContext = new LoopContext(conditionNode.id());
        loopContexts.push(loopContext);

        List<FlowTail> bodyTails = buildStatement(
                whileStmt.getBody(),
                List.of(new FlowTail(conditionNode.id(), CfgConstants.EDGE_TRUE))
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
                CfgConstants.NODE_CONDITION,
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
                List.of(new FlowTail(conditionNode.id(), CfgConstants.EDGE_TRUE))
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
        AnalyzerNode breakNode = createNode(CfgConstants.NODE_ACTION, CfgConstants.EDGE_BREAK, breakStmt);
        nodes.add(breakNode);
        connectTails(incomingTails, breakNode.id());

        if (loopContexts.isEmpty()) {
            return List.of(new FlowTail(breakNode.id(), null));
        }

        loopContexts.peek().breakTails().add(new FlowTail(breakNode.id(), CfgConstants.EDGE_BREAK));
        return List.of();
    }

    private List<FlowTail> buildContinueStatement(ContinueStmt continueStmt, List<FlowTail> incomingTails) {
        AnalyzerNode continueNode = createNode(CfgConstants.NODE_ACTION, CfgConstants.EDGE_CONTINUE, continueStmt);
        nodes.add(continueNode);
        connectTails(incomingTails, continueNode.id());

        if (loopContexts.isEmpty()) {
            return List.of(new FlowTail(continueNode.id(), null));
        }

        LoopContext loopContext = loopContexts.peek();
        loopContext.registerContinue();
        connectBackEdges(List.of(new FlowTail(continueNode.id(), CfgConstants.EDGE_CONTINUE)), loopContext.continueTargetNodeId());
        return List.of();
    }

    private List<FlowTail> buildThrowStatement(ThrowStmt throwStmt, List<FlowTail> incomingTails) {
        AnalyzerNode throwNode = createNode(CfgConstants.NODE_ACTION, "throw " + throwStmt.getExpression(), throwStmt, true);
        nodes.add(throwNode);
        connectTails(incomingTails, throwNode.id());
        connectEdge(throwNode.id(), exceptionExitNode().id(), CfgConstants.EDGE_THROWS, false);
        return List.of();
    }

    private List<FlowTail> buildReturnStatement(ReturnStmt returnStmt, List<FlowTail> incomingTails) {
        String label = returnStmt.getExpression()
                .map(expression -> "return " + expression)
                .orElse("return");
        AnalyzerNode returnNode = createNode(CfgConstants.NODE_ACTION, label, returnStmt);
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
            return createNode(CfgConstants.NODE_ACTION, label, sourceNode);
        }

        String methodName = methodCallExpr.getNameAsString();
        String receiver = methodCallExpr.getScope().map(Expression::toString).orElse(null);
        String callTarget = receiver == null ? methodIndex.getOrDefault(methodName, methodName) : null;
        return nodeBuilder(CfgConstants.NODE_ACTION, label, sourceNode)
                .isCall(true)
                .callTarget(callTarget)
                .callReceiver(receiver)
                .callMethodName(methodName)
                .build();
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
        exits.add(new FlowTail(conditionNodeId, CfgConstants.EDGE_FALSE));
        exits.addAll(loopContext.breakTails());
        return List.copyOf(exits);
    }

    private AnalyzerNode createNode(String type, String label, Node sourceNode) {
        return createNode(type, label, sourceNode, false);
    }

    private AnalyzerNode createNode(String type, String label, Node sourceNode, boolean isError) {
        return nodeBuilder(type, label, sourceNode)
                .isError(isError)
                .build();
    }

    private AnalyzerNode.Builder nodeBuilder(String type, String label, Node sourceNode) {
        return AnalyzerNode.builder()
                .id("node-" + nodeSequence++)
                .type(type)
                .label(label)
                .line(sourceNode.getBegin().map(position -> position.line).orElse(-1));
    }

    private AnalyzerNode exceptionExitNode() {
        if (exceptionExitNode == null) {
            exceptionExitNode = AnalyzerNode.builder()
                    .id(CfgConstants.EXIT_EXCEPTION_ID)
                    .type(CfgConstants.NODE_ACTION)
                    .label(CfgConstants.EXIT_EXCEPTION_LABEL)
                    .line(-1)
                    .isError(true)
                    .build();
            nodes.add(exceptionExitNode);
        }
        return exceptionExitNode;
    }

    private void connectTails(List<FlowTail> tails, String targetNodeId) {
        for (FlowTail tail : tails) {
            connectEdge(tail.nodeId(), targetNodeId, tail.label() == null ? CfgConstants.EDGE_NEXT : tail.label(), false);
        }
    }

    private void connectBackEdges(List<FlowTail> tails, String targetNodeId) {
        for (FlowTail tail : tails) {
            connectEdge(tail.nodeId(), targetNodeId, tail.label() == null ? CfgConstants.EDGE_BACK : tail.label(), true);
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

    @FunctionalInterface
    private interface StatementHandler {
        List<FlowTail> handle(Statement statement, List<FlowTail> incomingTails);
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
