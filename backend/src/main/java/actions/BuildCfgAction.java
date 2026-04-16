package actions;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
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
        visitor.completePendingBranches(context);

        return new GraphDTO(List.copyOf(visitor.nodes), List.copyOf(visitor.edges));
    }

    private static final class CfgVisitor extends VoidVisitorAdapter<FlowContext> {
        private final List<AnalyzerNode> nodes = new ArrayList<>();
        private final List<AnalyzerEdge> edges = new ArrayList<>();
        private int nodeSequence = 1;
        private int edgeSequence = 1;

        @Override
        public void visit(BlockStmt blockStmt, FlowContext context) {
            for (Statement statement : blockStmt.getStatements()) {
                statement.accept(this, context);
            }
        }

        @Override
        public void visit(ExpressionStmt expressionStmt, FlowContext context) {
            Expression expression = expressionStmt.getExpression();

            if (expression.isVariableDeclarationExpr()) {
                expression.asVariableDeclarationExpr().accept(this, context);
                return;
            }

            if (expression.isAssignExpr()) {
                expression.asAssignExpr().accept(this, context);
                return;
            }

            super.visit(expressionStmt, context);
        }

        @Override
        public void visit(VariableDeclarationExpr variableDeclarationExpr, FlowContext context) {
            AnalyzerNode node = createNode("action", variableDeclarationExpr.toString(), variableDeclarationExpr);
            nodes.add(node);
            connectTails(context.tails, node.id());
            context.tails = List.of(new FlowTail(node.id(), null));
        }

        @Override
        public void visit(AssignExpr assignExpr, FlowContext context) {
            AnalyzerNode node = createNode("action", assignExpr.toString(), assignExpr);
            nodes.add(node);
            connectTails(context.tails, node.id());
            context.tails = List.of(new FlowTail(node.id(), null));
        }

        @Override
        public void visit(IfStmt ifStmt, FlowContext context) {
            AnalyzerNode conditionNode = createNode("condition", ifStmt.getCondition().toString(), ifStmt);
            nodes.add(conditionNode);
            connectTails(context.tails, conditionNode.id());

            FlowContext trueContext = new FlowContext(List.of(new FlowTail(conditionNode.id(), "true")));
            ifStmt.getThenStmt().accept(this, trueContext);

            FlowContext falseContext = new FlowContext(List.of(new FlowTail(conditionNode.id(), "false")));
            ifStmt.getElseStmt().ifPresent(elseStmt -> elseStmt.accept(this, falseContext));

            List<FlowTail> mergedTails = new ArrayList<>();
            mergedTails.addAll(trueContext.tails);
            mergedTails.addAll(falseContext.tails);
            context.tails = List.copyOf(mergedTails);
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

        private void completePendingBranches(FlowContext context) {
            boolean hasPendingBranch = context.tails.stream().anyMatch(tail -> tail.label() != null);

            if (!hasPendingBranch) {
                return;
            }

            AnalyzerNode endNode = new AnalyzerNode("node-" + nodeSequence++, "end", "end", -1);
            nodes.add(endNode);
            connectTails(context.tails, endNode.id());
            context.tails = List.of(new FlowTail(endNode.id(), null));
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
