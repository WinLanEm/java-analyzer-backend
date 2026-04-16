package memory;

import java.util.List;
import java.util.Map;

public record RuntimeObject(
        long id,
        String className,
        Map<String, Object> fields,
        List<Object> values
) {
}
