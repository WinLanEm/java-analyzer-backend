package actions;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.GraphDTO;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class BuildCfgAction {

    public GraphDTO execute(String code) {
        CompilationUnit compilationUnit = StaticJavaParser.parse(code);
        CfgVisitor visitor = new CfgVisitor();
        FlowContext context = new FlowContext(List.of());

        visitor.visit(compilationUnit, context);

        return new GraphDTO(List.copyOf(visitor.nodes), List.copyOf(visitor.edges));
    }

    private static final class CfgVisitor extends VoidVisitorAdapter<FlowContext> {
        private final List<AnalyzerNode> nodes = new ArrayList<>();
        private final List<AnalyzerEdge> edges = new ArrayList<>();
        private final Deque<LoopContext> loopContexts = new ArrayDeque<>();
        private int nodeSequence = 1;
        private int edgeSequence = 1;

        @Override
        public void visit(BlockStmt blockStmt, FlowContext context) {
            context.tails = buildBlock(blockStmt, context.tails);
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

        private List<FlowTail> buildExpressionStatement(
                ExpressionStmt expressionStatement,
                List<FlowTail> incomingTails
        ) {
            Expression expression = expressionStatement.getExpression();
            return buildActionNode(expression.toString(), expressionStatement, incomingTails);
        }

        private List<FlowTail> buildActionNode(String label, Node sourceNode, List<FlowTail> incomingTails) {
            AnalyzerNode node = createNode("action", label, sourceNode);

            nodes.add(node);
            connectTails(incomingTails, node.id());
            return List.of(new FlowTail(node.id(), null));
        }

        private List<AnalyzerNode> createUpdateNodes(ForStmt forStmt) {
            List<AnalyzerNode> updateNodes = new ArrayList<>();
            for (Expression update : forStmt.getUpdate()) {
                updateNodes.add(createNode("action", update.toString(), update));
            }
            return updateNodes;
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
            return new AnalyzerNode(
                    "node-" + nodeSequence++,
                    type,
                    label,
                    sourceNode.getBegin().map(position -> position.line).orElse(-1)
            );
        }

        private void connectTails(List<FlowTail> tails, String targetNodeId) {
            for (FlowTail tail : tails) {
                edges.add(new AnalyzerEdge(
                        "edge-" + edgeSequence++,
                        tail.nodeId(),
                        targetNodeId,
                        tail.label() == null ? "next" : tail.label(),
                        false
                ));
            }
        }

        private void connectBackEdges(List<FlowTail> tails, String targetNodeId) {
            for (FlowTail tail : tails) {
                edges.add(new AnalyzerEdge(
                        "edge-" + edgeSequence++,
                        tail.nodeId(),
                        targetNodeId,
                        tail.label() == null ? "back" : tail.label(),
                        true
                ));
            }
        }
    }

    private static final class FlowContext {
        private List<FlowTail> tails;

        private FlowContext(List<FlowTail> tails) {
            this.tails = tails;
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
