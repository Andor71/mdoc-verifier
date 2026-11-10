package com.mdoc.verifier.dto.output;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

public class mDocDto {

    private String version;

    private List<Document> documents;

    // DocType -> ErrorCode
    private List<Map<String, Integer>> documentErrors;

    private Integer status;
}
