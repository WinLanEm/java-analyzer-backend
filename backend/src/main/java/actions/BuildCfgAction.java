package actions;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import dto.AnalyzerEdge;
import dto.AnalyzerNode;
import dto.GraphDTO;

import java.util.ArrayList;
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

        private List<FlowTail> buildExpressionStatement(
                ExpressionStmt expressionStatement,
                List<FlowTail> incomingTails
        ) {
            Expression expression = expressionStatement.getExpression();
            AnalyzerNode node = createNode("action", expression.toString(), expressionStatement);

            nodes.add(node);
            connectTails(incomingTails, node.id());
            return List.of(new FlowTail(node.id(), null));
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
                        tail.label() == null ? "next" : tail.label()
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
}
