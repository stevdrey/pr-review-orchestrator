package io.github.stevdrey.prrevieworchestrator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {
    @LocalServerPort
    int port;

    @Test
    void healthEndpointReportsUp() throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("UP", JsonPath.read(response.body(), "$.status"), response.body());
        }
    }
}
