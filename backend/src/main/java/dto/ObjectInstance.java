package dto;

import java.util.List;
import java.util.Map;

public record ObjectInstance(
        long id,
        String className,
        List<Object> values,
        Map<String, Object> fields
) {
}
