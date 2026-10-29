package com.mdoc.verifier.controller;

import com.mdoc.verifier.dto.mDocInputDto;
import com.mdoc.verifier.service.DecodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@Slf4j
@RestController
@RequestMapping("/decode")
@RequiredArgsConstructor
public class DecodeController {

    private final DecodeService decodeService;

    @PostMapping("")
    public JsonNode decode(@RequestBody @Validated mDocInputDto request) {

        //TODO Sanitize the input to prevent any malicious content

        return decodeService.decode(request); // Placeholder for the actual decoding logic
    }
}
