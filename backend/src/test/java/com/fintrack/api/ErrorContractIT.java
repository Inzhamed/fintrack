package com.fintrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The single error contract, exercised through every branch that produces it.
 * <p>
 * Every client in the system branches on {@code error.code}, so the shape is as much a public
 * interface as any endpoint. These cases are mostly framework-level failures - unreadable
 * JSON, a malformed UUID, an unmapped path - which is exactly where a second error shape
 * tends to leak out, because those responses are produced by Spring rather than by
 * application code.
 */
class ErrorContractIT extends AbstractIntegrationTest {

    /** Asserts the envelope is complete, whatever produced it. */
    private void assertWellFormed(JsonNode body, String expectedCode, String path) {
        JsonNode error = body.get("error");
        assertThat(error).as("every failure is wrapped in an 'error' object").isNotNull();
        assertThat(error.get("code").asString()).isEqualTo(expectedCode);
        assertThat(error.get("message").asString()).isNotBlank();
        assertThat(error.get("path").asString()).isEqualTo(path);
        assertThat(error.get("timestamp").asString()).isNotBlank();
    }

    @Test
    @DisplayName("a body that is not JSON at all")
    void handlesUnparseableBody() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        JsonNode body = json(mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertWellFormed(body, "MALFORMED_REQUEST", "/api/v1/transactions");
    }

    @Test
    @DisplayName("a field whose value is the wrong type")
    void handlesInvalidFieldFormat() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        // "type" is an enum; "SIDEWAYS" is not one of its constants.
        JsonNode body = json(mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"SIDEWAYS","amount":100}"""))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertWellFormed(body, "MALFORMED_REQUEST", "/api/v1/transactions");
        // The offending field is named, so a client can point at the right input.
        assertThat(body.get("error").get("details").get("field").asString()).isEqualTo("type");
    }

    @Test
    @DisplayName("a date that is not a date")
    void handlesInvalidDateFormat() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"EXPENSE","amount":100,"occurredOn":"last Tuesday"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.error.details.field").value("occurredOn"));
    }

    @Test
    @DisplayName("a path variable that is not a valid UUID")
    void handlesMalformedPathVariable() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        JsonNode body = json(mockMvc.perform(authed(get("/api/v1/transactions/not-a-uuid"), token))
                .andExpect(status().isBadRequest())
                .andReturn());

        assertWellFormed(body, "VALIDATION_FAILED", "/api/v1/transactions/not-a-uuid");
        assertThat(body.get("error").get("details").get("parameter").asString()).isEqualTo("id");
    }

    @Test
    @DisplayName("a query parameter with the wrong type")
    void handlesBadQueryParameter() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(get("/api/v1/transactions?from=yesterday"), token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("an unmapped path still answers in the standard shape")
    void handlesUnmappedPath() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        // Produced by Spring, not by application code - the case most likely to leak a
        // different body if the handler did not cover it.
        JsonNode body = json(mockMvc.perform(authed(get("/api/v1/nonexistent"), token))
                .andExpect(status().isNotFound())
                .andReturn());

        assertWellFormed(body, "NOT_FOUND", "/api/v1/nonexistent");
    }

    @Test
    @DisplayName("an unauthenticated request to a protected route")
    void handlesMissingAuthentication() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized())
                .andReturn());

        // Written by the security entry point rather than the controller advice, so this is a
        // second code path that has to produce the same envelope.
        assertWellFormed(body, "UNAUTHENTICATED", "/api/v1/transactions");
    }

    @Test
    @DisplayName("a bearer token that is not a token")
    void handlesGarbageToken() throws Exception {
        mockMvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer nonsense"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a well-formed but unknown id")
    void handlesUnknownResource() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        JsonNode body = json(mockMvc.perform(
                        authed(get("/api/v1/transactions/" + UUID.randomUUID()), token))
                .andExpect(status().isNotFound())
                .andReturn());

        assertWellFormed(body, "NOT_FOUND", body.get("error").get("path").asString());
        assertThat(body.get("error").get("details").get("resource").asString())
                .isEqualTo("Transaction");
    }

    @Test
    @DisplayName("validation failures name every offending field, not just the first")
    void reportsAllInvalidFields() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        JsonNode body = json(mockMvc.perform(authed(post("/api/v1/bills"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","amount":-5,"recurrence":"MONTHLY","dueDay":99}"""))
                .andExpect(status().isBadRequest())
                .andReturn());

        JsonNode details = body.get("error").get("details");
        // Returning one at a time makes a user fix a form field by field.
        assertThat(details.get("name")).isNotNull();
        assertThat(details.get("amount")).isNotNull();
        assertThat(details.get("dueDay")).isNotNull();
    }

    @Test
    @DisplayName("an internal message is never returned to the client")
    void neverLeaksInternals() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        String body = mockMvc.perform(authed(get("/api/v1/transactions/not-a-uuid"), token))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // A stack frame, a SQL fragment or a class name in an error body tells an attacker
        // about the stack, the schema and the framework versions in use.
        assertThat(body)
                .doesNotContain("com.fintrack")
                .doesNotContain("org.springframework")
                .doesNotContain("Exception")
                .doesNotContain("java.lang")
                .doesNotContain("select ");
    }

    @Test
    @DisplayName("the wrong HTTP method is a 405, not a 500")
    void handlesWrongMethod() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        // Using the wrong verb is entirely the caller's mistake. Reporting it as a server
        // error sends people hunting for an outage that is not there.
        JsonNode body = json(mockMvc.perform(authed(put("/api/v1/transactions"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andReturn());

        assertWellFormed(body, "METHOD_NOT_ALLOWED", "/api/v1/transactions");
    }

    @Test
    @DisplayName("a body the endpoint cannot consume is a 415")
    void handlesUnsupportedMediaType() throws Exception {
        String token = registerAndLogin("hamed@example.com");

        mockMvc.perform(authed(post("/api/v1/transactions"), token)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("type=EXPENSE"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    @DisplayName("a conflict carries the CONFLICT family, not a generic 400")
    void handlesConflict() throws Exception {
        String token = registerAndLogin("hamed@example.com");
        String category = """
                {"name":"Coffee","type":"EXPENSE"}""";

        mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(category))
                .andExpect(status().isCreated());

        JsonNode body = json(mockMvc.perform(authed(post("/api/v1/categories"), token)
                        .contentType(MediaType.APPLICATION_JSON).content(category))
                .andExpect(status().isConflict())
                .andReturn());

        assertWellFormed(body, "DUPLICATE_RESOURCE", "/api/v1/categories");
    }
}
