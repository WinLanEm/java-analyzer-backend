package constants;

import java.util.regex.Pattern;

public final class SimulationConstants {
    public static final String RUNTIME_ERROR_NODE_ID = "runtime-error";
    public static final int MAX_STEPS = 10_000;

    public static final Pattern DECLARATION_PATTERN = Pattern.compile(
            "^(?:final\\s+)?[A-Za-z_$][A-Za-z0-9_$]*(?:<[^>]+>)?(?:\\[\\])?\\s+(.+)$"
    );
    public static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    public static final Pattern COMPOUND_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*([+\\-*/])=\\s*(.+)$"
    );
    public static final Pattern POSTFIX_UPDATE_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*(\\+\\+|--)$"
    );
    public static final Pattern PREFIX_UPDATE_PATTERN = Pattern.compile(
            "^(\\+\\+|--)\\s*([A-Za-z_$][A-Za-z0-9_$]*)$"
    );
    public static final Pattern ARRAY_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[([^\\]]+)]\\s*=\\s*(.+)$"
    );
    public static final Pattern FIELD_ASSIGNMENT_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*(.+)$"
    );
    public static final Pattern ARRAY_ACCESS_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[([^\\]]+)]$"
    );
    public static final Pattern ARRAY_LENGTH_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.length$"
    );
    public static final Pattern FIELD_ACCESS_PATTERN = Pattern.compile(
            "^([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)$"
    );
    public static final Pattern ARRAY_CREATION_INITIALIZER_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[\\]\\s*\\{(.*)}$"
    );
    public static final Pattern ARRAY_CREATION_SIZED_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\[(.+)]$"
    );
    public static final Pattern OBJECT_CREATION_PATTERN = Pattern.compile(
            "^new\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(.*\\)$"
    );
    public static final Pattern INTRINSIC_CALL_PATTERN = Pattern.compile(
            "^(Math\\.(?:max|min|abs|pow)|String\\.valueOf)\\s*\\((.*)\\)$"
    );
    public static final Pattern COMPARISON_PATTERN = Pattern.compile(
            "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$"
    );
    public static final Pattern ARITHMETIC_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*\\s*\\[[^\\]]+]|[A-Za-z_$][A-Za-z0-9_$]*\\.[A-Za-z_$][A-Za-z0-9_$]*|[A-Za-z_$][A-Za-z0-9_$]*|-?\\d+|[+\\-*/]"
    );

    private SimulationConstants() {
    }
}
