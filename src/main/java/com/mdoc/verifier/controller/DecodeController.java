package com.mdoc.verifier.controller;

import com.mdoc.verifier.dto.mDocInputDto;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/decode")
public class DecodeController {

    @PostMapping("")
    public JsonNode decode(@RequestBody @Validated mDocInputDto request) {

        return null; // Placeholder for the actual decoding logic
    }
}
