package dto;

import java.util.List;

public record ObjectInstance(
        long id,
        String className,
        List<Object> values
) {
}
