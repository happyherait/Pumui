package io.github.happyherait.pumui.app;

import io.github.happyherait.pumui.app.Models.Detail;
import io.github.happyherait.pumui.app.Models.DraftInput;
import io.github.happyherait.pumui.app.Models.ReviewInput;
import io.github.happyherait.pumui.app.Models.RevisionContent;
import io.github.happyherait.pumui.app.Models.Suggestions;
import io.github.happyherait.pumui.app.Models.Summary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/** 사용자 식별은 데모용 헤더다. 실제 운영에서는 SSO로 교체한다. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private static final String USER = "X-Pumui-User";

    private final PurchaseRequestService service;
    private final AiService ai;
    private final JdbcClient db;
    private final ObjectMapper json;

    public ApiController(PurchaseRequestService service, AiService ai, JdbcClient db, ObjectMapper json) {
        this.service = service;
        this.ai = ai;
        this.db = db;
        this.json = json;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog() throws IOException {
        Map<?, ?> seed = json.readValue(new ClassPathResource("seed.json").getContentAsString(StandardCharsets.UTF_8), Map.class);
        return Map.of(
                "users", db.sql("SELECT id, name, dept, role_code FROM app_users ORDER BY id")
                        .query((rs, n) -> new Models.User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4))).list(),
                "projects", db.sql("""
                        SELECT code, name, budget_total - budget_used, TO_CHAR(end_date, 'YYYY-MM-DD') FROM rnd_projects ORDER BY code
                        """).query((rs, n) -> Map.of("code", rs.getString(1), "name", rs.getString(2),
                                "available", rs.getLong(3), "endDate", rs.getString(4))).list(),
                "suppliers", db.sql("SELECT code, name FROM suppliers ORDER BY code")
                        .query((rs, n) -> Map.of("code", rs.getString(1), "name", rs.getString(2))).list(),
                "items", db.sql("SELECT code, name, category, unit FROM items ORDER BY code")
                        .query((rs, n) -> Map.of("code", rs.getString(1), "name", rs.getString(2),
                                "category", rs.getString(3), "unit", rs.getString(4))).list(),
                "samples", seed.get("samples"));
    }

    @GetMapping("/requests")
    public List<Summary> list() {
        return service.list();
    }

    @PostMapping("/requests")
    public Detail create(@RequestHeader(USER) String user, @RequestBody RevisionContent content) {
        return service.detail(service.create(user, content));
    }

    @GetMapping("/requests/{id}")
    public Detail detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/requests/{id}/revisions")
    public Detail revise(@PathVariable long id, @RequestHeader(USER) String user, @RequestBody RevisionContent content) {
        service.addRevision(id, user, content);
        return service.detail(id);
    }

    @PostMapping("/requests/{id}/reviews")
    public Detail review(@PathVariable long id, @RequestHeader(USER) String user, @RequestBody ReviewInput input) {
        service.review(id, user, input);
        return service.detail(id);
    }

    @PostMapping("/requests/{id}/merge")
    public Detail merge(@PathVariable long id, @RequestHeader(USER) String user) {
        service.merge(id, user);
        return service.detail(id);
    }

    @PostMapping("/requests/{id}/close")
    public Detail close(@PathVariable long id, @RequestHeader(USER) String user) {
        service.close(id, user);
        return service.detail(id);
    }

    @PostMapping("/ai/draft")
    public RevisionContent draft(@RequestBody DraftInput input) {
        return ai.draft(input);
    }

    @PostMapping("/requests/{id}/ai/suggestions")
    public Suggestions suggest(@PathVariable long id) {
        return ai.suggest(service.detail(id));
    }
}
