package com.mdoc.verifier.controller;

import static com.mdoc.verifier.support.MdocFixtures.deviceResponse;
import static com.mdoc.verifier.support.MdocFixtures.document;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mdoc.verifier.dto.mDocInputDto;
import com.mdoc.verifier.config.CborConfig;
import com.mdoc.verifier.exception.GlobalExceptionHandler;
import com.mdoc.verifier.service.DecodeService;
import com.mdoc.verifier.service.IssuerAuthService;
import com.mdoc.verifier.service.NameSpaceService;
import com.mdoc.verifier.support.Cbor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** HTTP layer with the real services: JSON in, JSON or RFC 9457 problem+json out. */
@WebMvcTest(controllers = {DecodeController.class, HealthController.class})
@Import({CborConfig.class, DecodeService.class, NameSpaceService.class, IssuerAuthService.class, GlobalExceptionHandler.class})
class DecodeControllerTest {

    @Autowired
    private MockMvc mvc;

    private ResultActions postDecode(final String body) throws Exception {
        return mvc.perform(post("/decode").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String request(final String mdoc, final String inputType) {
        return "{\"mdoc\":\"" + mdoc + "\",\"inputType\":\"" + inputType + "\"}";
    }

    @Test
    void validInputReturnsDecodedJson() throws Exception {
        postDecode(request(Cbor.hex(deviceResponse(document())), "HEX"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.documents[0].issuerSigned.issuerAuth.protectedHeader.alg").value(-7))
                .andExpect(jsonPath("$.warnings").isArray());
    }

    @Test
    void decodeFailureIsProblemJsonWithCodeAndPath() throws Exception {
        postDecode(request("0102", "HEX"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Trailing CBOR data"))
                .andExpect(jsonPath("$.code").value("CBOR_TRAILING_DATA"))
                .andExpect(jsonPath("$.path").value("$"))
                .andExpect(jsonPath("$.detail", not(empty())))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void invalidEncodingIsProblemJson() throws Exception {
        postDecode(request("zz", "HEX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT_ENCODING"));
    }

    @Test
    void requestValidationFailsWithBadRequest() throws Exception {
        postDecode("{\"inputType\":\"HEX\"}").andExpect(status().isBadRequest());
        postDecode(request("", "HEX")).andExpect(status().isBadRequest());
        postDecode("{\"mdoc\":\"00\"}").andExpect(status().isBadRequest());
        postDecode(request("a".repeat(mDocInputDto.MAX_MDOC_LENGTH + 1), "HEX")).andExpect(status().isBadRequest());
    }

    @Test
    void validationFailureIsProblemJsonNamingTheField() throws Exception {
        postDecode(request("a".repeat(mDocInputDto.MAX_MDOC_LENGTH + 1), "HEX"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Request validation failed: mdoc: mdoc field cannot exceed 100000 characters"));
    }

    @Test
    void unknownInputTypeOrBrokenJsonFailsWithBadRequest() throws Exception {
        postDecode(request("00", "BASE32")).andExpect(status().isBadRequest());
        postDecode("{not json").andExpect(status().isBadRequest());
    }

    @Test
    void healthEndpointAnswers() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().string("Mdoc Verifier is up and running!"));
    }
}
