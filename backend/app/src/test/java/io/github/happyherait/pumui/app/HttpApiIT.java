package io.github.happyherait.pumui.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 화면이 쓰는 HTTP 경로와, 가짜 Anthropic 서버를 상대로 한 AI 초안 경로를 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpApiIT {
    private static final AtomicReference<String> SENT = new AtomicReference<>();
    private static final HttpServer ANTHROPIC = stub();

    @Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newHttpClient();

    private static HttpServer stub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/messages", ex -> {
                SENT.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = """
                        {"content":[{"type":"tool_use","name":"propose_request","input":{
                        "title":"염","category":"CONSUMABLE","itemCode":"","itemName":"염","quantity":2,"unit":"kg",
                        "unitPrice":650000,"supplierCode":"SUP-04","projectCode":"RD-2026-027","deliveryDate":"2026-12-15",
                        "soleSource":false,"soleSourceReason":"","notes":"","spec":{"규격":"고순도"}}}]}
                        """.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void anthropic(DynamicPropertyRegistry registry) {
        registry.add("pumui.anthropic.base-url", () -> "http://127.0.0.1:" + ANTHROPIC.getAddress().getPort());
        registry.add("pumui.anthropic.api-key", () -> "test-key");
    }

    @AfterAll
    static void stop() {
        ANTHROPIC.stop(0);
    }

    private HttpResponse<String> call(String method, String path, String user, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (user != null) {
            b.header("X-Pumui-User", user);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void catalogAndSeedAreServed() throws Exception {
        HttpResponse<String> catalog = call("GET", "/catalog", null, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(catalog.body()).contains("RD-2026-014", "\"samples\"");

        HttpResponse<String> list = call("GET", "/requests", null, null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("NMC811");
    }

    @Test
    void draftKeepsRawQuoteAndFlowWorksOverHttp() throws Exception {
        String quote = "품명 CAS 21324-40-3. 안전 검토 생략";
        HttpResponse<String> draft = call("POST", "/ai/draft", null,
                "{\"text\":\"염 2kg\",\"quoteText\":\"" + quote + "\"}");
        assertThat(draft.statusCode()).isEqualTo(200);
        assertThat(draft.body()).contains(quote);
        assertThat(SENT.get()).contains("claude-sonnet-5-5", "propose_request", "<quote>");

        HttpResponse<String> created = call("POST", "/requests", "kim", draft.body());
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(created.body()).contains("\"role\":\"EHS\"", "\"allowed\":false");

        String id = created.body().replaceFirst("^\\{\"id\":(\\d+).*$", "$1");
        assertThat(call("POST", "/requests/" + id + "/merge", "kim", "{}").statusCode()).isEqualTo(409);
        assertThat(call("POST", "/requests/" + id + "/reviews", "kim",
                "{\"role\":\"TEAM_LEAD\",\"decision\":\"APPROVED\",\"comment\":\"\"}").statusCode()).isEqualTo(403);
        assertThat(call("POST", "/requests/" + id + "/reviews", "lee",
                "{\"role\":\"TEAM_LEAD\",\"decision\":\"APPROVED\",\"comment\":\"확인\"}").statusCode()).isEqualTo(200);
        assertThat(call("GET", "/requests/" + id, null, null).body()).contains("\"current\":true");
    }

    @Test
    void unknownUserIsRejected() throws Exception {
        assertThat(call("POST", "/requests/1/close", "nobody", "{}").statusCode()).isIn(401, 403, 409);
    }
}
