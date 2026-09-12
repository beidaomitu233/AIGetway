package com.lightai.admin.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.lightai.admin.alias.*;
import com.lightai.admin.audit.AuditService;
import com.lightai.admin.check.ChannelCheckService;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.admin.impact.ImpactService;
import com.lightai.admin.query.PageResultFactory;
import com.lightai.admin.upstream.*;
import com.lightai.admin.web.*;
import com.lightai.client.json.ProtocolJson;
import com.lightai.client.protocol.Roles;
import com.lightai.spi.auth.AuthContext;
import com.lightai.storage.alias.*;
import com.lightai.storage.audit.JdbcAuditRepository;
import com.lightai.storage.batch.JdbcBatchCheckRepository;
import com.lightai.storage.channel.*;
import com.lightai.storage.check.JdbcChannelCheckRecordRepository;
import com.lightai.storage.crypto.AesGcmSecretCipher;
import com.lightai.storage.draft.*;
import com.lightai.storage.reference.JdbcConfigReferenceRepository;
import com.lightai.storage.runtime.*;
import com.lightai.storage.schema.DefaultSchemaMigrator;
import com.lightai.storage.upstream.JdbcUpstreamModelRepository;
import java.time.Clock;
import java.util.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** V2 resource HTTP tests use migrated H2 and real services, repositories, transactions and AES-GCM. */
class ResourceApiContractTest {
    private MockMvc mvc;
    private JdbcTemplate sql;
    private String channel;
    private String otherChannel;
    private String model;
    private String virtualModel;
    private String otherVirtualModel;

    @BeforeEach
    void setUp() throws Exception {
        var db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:resources_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        new DefaultSchemaMigrator(db).migrate();
        sql = new JdbcTemplate(db);
        var tx = new DataSourceTransactionManager(db);
        var audit = new AuditService(new JdbcAuditRepository(), db, tx, (record, cause) -> { });
        var changes = new JdbcDraftChangeRepository();
        var drafts = new DraftWriteService(db, tx, new JdbcDraftStateRepository(), changes, audit);
        var channels = new JdbcChannelRepository();
        var credentials = new JdbcChannelCredentialRepository();
        var refs = new JdbcConfigReferenceRepository("light_ai");
        var states = new JdbcObjectRuntimeStateRepository();
        var writer = new JdbcRuntimeStateWriter("light_ai");
        var checks = new JdbcChannelCheckRecordRepository("light_ai");
        var pages = new PageResultFactory(Clock.systemUTC());
        var impact = new ImpactService(refs);
        var channelService = new ChannelService(db, channels, refs, states, writer, checks,
                changes, drafts, impact, new ProviderTypeRegistry(null), new TargetUrlPolicy(false), pages, "TEST");
        byte[] master = new byte[32];
        new java.security.SecureRandom().nextBytes(master);
        var keyService = new ChannelCredentialService(db, credentials, channels, states, changes, drafts,
                new AesGcmSecretCipher(Base64.getEncoder().encodeToString(master), "test-key"), pages, "TEST");
        var checkService = new ChannelCheckService(db, channels, refs, checks, writer, List.of(), "TEST");
        var models = new JdbcUpstreamModelRepository();
        var routes = new JdbcCandidateRepository();
        var aliases = new JdbcAliasRepository();
        var modelService = new UpstreamModelService(db, models, channels, routes, states, changes,
                drafts, impact, pages, "TEST");
        var imports = new ModelImportService(db, channels, models, drafts,
                new JdbcBatchCheckRepository("light_ai"), List.of(), "TEST");
        var aliasService = new ModelAliasService(db, aliases, routes, changes, drafts, impact, pages, "TEST");
        var routeService = new RouteCandidateService(db, routes, aliases, models, channels, drafts, checkService, "TEST");
        mvc = MockMvcBuilders.standaloneSetup(new ChannelController(channelService, checkService),
                        new ChannelCredentialController(keyService), new UpstreamModelController(modelService, imports),
                        new ModelAliasController(aliasService, routeService))
                .setControllerAdvice(new AdminErrorHandler()).addFilters(new RequestIdFilter())
                .addInterceptors(new AdminAuthInterceptor(request -> {
                    String role = request.headers().get("x-test-role");
                    return role == null ? AuthContext.anonymous() : AuthContext.authenticated(
                            "test-user", "Test", Set.of(role), List.of());
                })).build();
        channel = createChannel("channel-one");
        otherChannel = createChannel("channel-two");
        model = id(call(post("/admin/upstream-models"), modelBody(channel, "model-one", false)));
        virtualModel = id(call(post("/admin/virtual-models"), "{\"alias\":\"virtual-one\",\"display_name\":\"Virtual one\",\"enabled\":false}"));
        otherVirtualModel = id(call(post("/admin/virtual-models"), "{\"alias\":\"virtual-two\",\"display_name\":\"Virtual two\",\"enabled\":false}"));
    }

    @Test
    void v2PathsAuthenticateAndPreserveDataEnvelope() throws Exception {
        for (String path : List.of("/admin/channels", "/admin/upstream-models", "/admin/virtual-models")) {
            mvc.perform(get(path)).andExpect(status().isForbidden());
            mvc.perform(get(path).header("x-test-role", Roles.AUDITOR))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isArray());
            mvc.perform(get(path).header("x-test-role", Roles.APPLICATION_OWNER))
                    .andExpect(status().isForbidden());
        }
        for (String old : List.of("/admin/providers", "/admin/provider-models", "/admin/model-aliases", "/admin/credential-pools/"+channel+"/credentials")) {
            mvc.perform(get(old).header("x-test-role", Roles.SYSTEM_ADMIN)).andExpect(status().isNotFound());
        }
    }

    @Test
    void channelRejectsDangerousUrlHeadersVersionsAndReadOnlyWrites() throws Exception {
        String body = channelBody("unsafe").replace("https://8.8.8.8/v1", "http://127.0.0.1");
        call(post("/admin/channels"), body).andExpect(status().isBadRequest());
        call(post("/admin/channels"), channelBody("unsafe").replace("\"weight\":10", "\"headers\":{\"Authorization\":\"sensitive-marker\"},\"weight\":10"))
                .andExpect(status().isBadRequest());
        call(put("/admin/channels/"+channel), channelBody("renamed").replace("\"weight\":10", "\"version\":999,\"weight\":10"))
                .andExpect(status().isConflict());
        mvc.perform(post("/admin/channels").header("x-test-role", Roles.OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON).content(channelBody("denied")))
                .andExpect(status().isForbidden());
    }

    @Test
    void channelSaveCommandExposesV2FieldsAndStatusSeparatesFromHealth() throws Exception {
        JsonNode detail = data(call(get("/admin/channels/"+channel), null).andExpect(status().isOk()));
        assertThat(detail.path("provider_type").asText()).isEqualTo("OPENAI");
        assertThat(detail.path("timeouts").path("connect_ms").asInt()).isEqualTo(1000);
        assertThat(detail.path("timeouts").path("read_ms").asInt()).isEqualTo(10000);
        assertThat(detail.path("timeouts").path("stream_idle_ms").asInt())
                .isEqualTo(com.lightai.client.channel.ChannelTimeouts.STREAM_IDLE_DEFAULT_MS);
        assertThat(detail.path("priority").asInt()).isEqualTo(5);
        assertThat(detail.path("weight").asInt()).isEqualTo(10);
        assertThat(detail.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(detail.has("enabled")).isFalse();
        assertThat(detail.has("connection_status")).isFalse();

        // 配置状态与健康分列：检测失败收敛的运行健康为 UNAVAILABLE，配置状态仍 ACTIVE
        sql.update("MERGE INTO object_runtime_state (id, entity_type, entity_id, connection_status, "
                        + "state_version, created_at, updated_at) KEY(entity_type, entity_id) VALUES (?, 'CHANNEL', ?, 'UNAVAILABLE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), channel);
        JsonNode afterCheck = data(call(get("/admin/channels/"+channel), null));
        assertThat(afterCheck.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(afterCheck.path("health").asText()).isEqualTo("UNAVAILABLE");

        // 列表过滤：provider_type/status
        JsonNode filtered = data(call(get("/admin/channels?provider_type=OPENAI&status=ACTIVE"), null));
        assertThat(filtered.path("total").asLong()).isEqualTo(2);
        JsonNode none = data(call(get("/admin/channels?provider_type=ANTHROPIC"), null));
        assertThat(none.path("total").asLong()).isZero();
    }

    @Test
    void keyPriorityIsEditableAndLastActiveKeyIsProtected() throws Exception {
        String key = id(call(post(keys(channel)), keyBody("only-key", "test-only-secret")));
        call(put(keys(channel)+"/"+key), "{\"name\":\"only-key\",\"priority\":3,\"weight\":10,\"version\":1,\"enabled\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entity.priority").value(3));
        assertThat(sql.queryForObject("SELECT priority FROM channel_credential WHERE id=?", Integer.class, key))
                .isEqualTo(3);

        // 渠道唯一可用 Key：停用与删除都拒绝
        call(post(keys(channel)+"/"+key+"/disable"), "{\"version\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OBJECT_IN_USE"));
        call(delete(keys(channel)+"/"+key), "{\"version\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("OBJECT_IN_USE"));

        // 新增第二个可用 Key 后，停用原 Key 成功（此前失败事务已回滚，version 仍为 2）
        String second = id(call(post(keys(channel)), keyBody("second-key", "test-only-second")));
        call(post(keys(channel)+"/"+key+"/disable"), "{\"version\":2}").andExpect(status().isOk());
        assertThat(sql.queryForObject("SELECT status FROM channel_credential WHERE id=?", String.class, key))
                .isEqualTo("DISABLED");
        assertThat(sql.queryForObject("SELECT count(*) FROM channel_credential "
                + "WHERE channel_id=? AND status='ACTIVE' AND deleted_at IS NULL", Long.class, channel)).isEqualTo(1);
        // second 未占用：second-key 可用计数为 1
        assertThat(second).isNotEqualTo(key);
    }

    @Test
    void keyIsEncryptedMaskedAndBoundToParentOnEveryAction() throws Exception {
        String secret = "test-only-" + UUID.randomUUID();
        String key = id(call(post(keys(channel)), keyBody("first-key", secret)));
        JsonNode stored = data(call(get(keys(channel)+"/"+key), null).andExpect(status().isOk()));
        assertThat(stored.toString()).doesNotContain(secret, "secret_ciphertext");
        byte[] ciphertext = sql.queryForObject("SELECT secret_ciphertext FROM channel_credential WHERE id=?", byte[].class, key);
        assertThat(new String(ciphertext, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain(secret);
        String wrong = keys(otherChannel)+"/"+key;
        for (MockHttpServletRequestBuilder request : List.of(get(wrong), put(wrong), post(wrong+"/rotate"),
                post(wrong+"/enable"), post(wrong+"/disable"), post(wrong+"/check"), delete(wrong))) {
            String body = "{\"name\":\"first-key\",\"weight\":10,\"version\":1,\"enabled\":true}";
            // Each command uses its exact schema so rejection comes from parent ownership.
            String uri = request.buildRequest(new org.springframework.mock.web.MockServletContext()).getRequestURI();
            if (uri.endsWith("rotate")) body="{\"version\":1,\"secret_value\":\"test-new-value\",\"secret_value_confirm\":\"test-new-value\"}";
            else if (uri.endsWith("enable") || uri.endsWith("disable") || request.buildRequest(new org.springframework.mock.web.MockServletContext()).getMethod().equals("DELETE")) body="{\"version\":1}";
            else if (uri.endsWith("check")) body="{}";
            else if (request.buildRequest(new org.springframework.mock.web.MockServletContext()).getMethod().equals("GET")) body=null;
            call(request,body).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("OBJECT_REFERENCE_INVALID"));
        }
        assertThat(sql.queryForObject("SELECT version FROM channel_credential WHERE id=?", Long.class,key)).isEqualTo(1);
    }

    @Test
    void rotationChangesCiphertextOnceAndRejectsStaleVersion() throws Exception {
        String key=id(call(post(keys(channel)), keyBody("rotate-key", "test-only-original")));
        byte[] before=sql.queryForObject("SELECT secret_ciphertext FROM channel_credential WHERE id=?",byte[].class,key);
        String command="{\"version\":1,\"secret_value\":\"test-only-rotated\",\"secret_value_confirm\":\"test-only-rotated\"}";
        call(post(keys(channel)+"/"+key+"/rotate"),command).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        byte[] after=sql.queryForObject("SELECT secret_ciphertext FROM channel_credential WHERE id=?",byte[].class,key);
        assertThat(after).isNotEqualTo(before);
        call(post(keys(channel)+"/"+key+"/rotate"),command).andExpect(status().isConflict());
        assertThat(sql.queryForObject("SELECT secret_version FROM channel_credential WHERE id=?",Long.class,key)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT secret_ciphertext FROM channel_credential WHERE id=?",byte[].class,key)).isEqualTo(after);
    }

    @Test
    void nestedKeyUpdateValidatesNameAndSecretMutation() throws Exception {
        String key = id(call(post(keys(channel)), keyBody("first-key", "test-only-first")));
        id(call(post(keys(channel)), keyBody("second-key", "test-only-second")));
        call(put(keys(channel)+"/"+key), "{\"name\":\"second-key\",\"priority\":5,\"weight\":10,\"version\":1,\"enabled\":true}")
                .andExpect(status().isBadRequest());
        call(put(keys(channel)+"/"+key), "{\"name\":\"x\",\"priority\":5,\"weight\":10,\"version\":1,\"enabled\":true}")
                .andExpect(status().isBadRequest());
        call(put(keys(channel)+"/"+key), "{\"name\":\"first-key\",\"secret_ref\":\"test-ref\",\"priority\":5,\"weight\":10,\"version\":1,\"enabled\":true}")
                .andExpect(status().isConflict());
        call(put(keys(channel)+"/"+key), "{\"name\":\"first-key\",\"weight\":10,\"version\":1,\"enabled\":true}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void routeAllowsZeroWeightAndRejectsDuplicateForeignParentAndPathMutation() throws Exception {
        String route = id(call(post(routes(virtualModel)), routeBody(channel,model,0,null)));
        call(post(routes(virtualModel)), routeBody(channel,model,1,null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_ROUTE_CANDIDATE"));
        call(put(routes(otherVirtualModel)+"/"+route), routeBody(channel,model,1,1))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("OBJECT_REFERENCE_INVALID"));
        call(delete(routes(otherVirtualModel)+"/"+route), "{\"version\":1}").andExpect(status().isUnprocessableEntity());
        call(post(routes(otherVirtualModel)+"/"+route+"/check"), "{}").andExpect(status().isUnprocessableEntity());
        call(put(routes(virtualModel)+"/"+route), routeBody(otherChannel,model,1,1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONFIG_FIELD_IMMUTABLE"));
        call(post(routes(virtualModel)), routeBody(otherChannel,model,1,null)).andExpect(status().isUnprocessableEntity());
        assertThat(sql.queryForObject("SELECT weight FROM route_candidate WHERE id=?", Integer.class,route)).isZero();
    }

    @Test
    void routeRuntimeStatusReflectsChannelHealth() throws Exception {
        String enabledModel = id(call(post("/admin/upstream-models"), modelBody(channel, "route-model", true)));
        id(call(post(routes(virtualModel)), routeBody(channel, enabledModel, 5, null)));
        JsonNode healthy = data(call(get(routes(virtualModel)), null)).get(0);
        assertThat(healthy.path("runtime_status").asText()).isEqualTo("AVAILABLE");
        sql.update("MERGE INTO object_runtime_state (id, entity_type, entity_id, connection_status, "
                        + "state_version, created_at, updated_at) KEY(entity_type, entity_id) VALUES (?, 'CHANNEL', ?, 'UNAVAILABLE', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), channel);
        JsonNode degraded = data(call(get(routes(virtualModel)), null)).get(0);
        assertThat(degraded.path("runtime_status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(degraded.path("excluded_reason").asText()).isEqualTo("渠道最近检测不可用");
    }

    @Test
    void routeValidationErrorsAndIdsAre400Not500() throws Exception {
        for (String body : List.of("{}", "null", "", routeBody(channel,model,-1,null))) {
            call(post(routes(virtualModel)),body).andExpect(status().isBadRequest());
        }
        call(put(routes(virtualModel)+"/reorder"),"{\"items\":[]}").andExpect(status().isBadRequest());
        for(String path : List.of("/admin/channels/1-1-1-1-1", "/admin/upstream-models/no-id",
                "/admin/virtual-models/no-id", "/admin/channels/no-id/credentials")) {
            call(get(path),null).andExpect(status().isBadRequest());
        }
    }

    @Test
    void modelRequiresRealIdCompletePriceAndImmutableChannel() throws Exception {
        call(post("/admin/upstream-models"),modelBody(channel,"missing",false).replace("\"model_id\":\"missing\",",""))
                .andExpect(status().isBadRequest());
        String enabled = modelBody(channel,"enabled-model",true)
                .replace("\"input_price\":\"1\",","");
        call(post("/admin/upstream-models"),enabled).andExpect(status().isBadRequest());
        call(put("/admin/upstream-models/"+model),modelBody(otherChannel,"model-one",false).replace("\"enabled\":false","\"version\":1,\"enabled\":false"))
                .andExpect(status().isConflict());
        call(post("/admin/channels/"+otherChannel+"/models"),modelBody(channel,"wrong-parent",false))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void noAdapterDoesNotReportSuccessfulChannelCheck() throws Exception {
        call(post("/admin/channels/"+channel+"/check"), "{\"mode\":\"CONNECTION_ONLY\",\"upstream_model_id\":\""+model+"\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("PROVIDER_ADAPTER_NOT_FOUND"));
        assertThat(sql.queryForObject("SELECT count(*) FROM channel_check_record",Long.class)).isZero();
    }

    @Test
    void batchRejectsInvalidOrUnavailableStorageWithoutSuccess() throws Exception {
        String body="{\"channel_id\":\""+channel+"\",\"upstream_model_ids\":[\""+model+"\"]}";
        String path="/admin/upstream-models/batch-check";
        call(post(path),body.replace(channel,otherChannel)).andExpect(status().isUnprocessableEntity());
        call(post(path),body.replace("}",",\"mode\":\"INVALID\"}")).andExpect(status().isBadRequest());
        assertThat(sql.queryForObject("SELECT count(*) FROM batch_check_job",Long.class)).isZero();
        // V5 已补齐批量检测存储列（BE-P21-006 解除）：合法请求真实落库为 PENDING 作业，
        // 终态由真实检测执行推进；此处只断言持久化，不伪造成功或失败。
        call(post(path),body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(sql.queryForObject("SELECT count(*) FROM batch_check_job",Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM batch_check_item",Long.class)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackResourceAndDraftRevision() throws Exception {
        long before=sql.queryForObject("SELECT count(*) FROM channel",Long.class);
        sql.execute("DROP TABLE audit_log");
        call(post("/admin/channels"),channelBody("must-rollback")).andExpect(status().is5xxServerError());
        assertThat(sql.queryForObject("SELECT count(*) FROM channel",Long.class)).isEqualTo(before);
    }

    private String createChannel(String name) throws Exception { return id(call(post("/admin/channels"),channelBody(name))); }
    private static String channelBody(String name) { return "{\"name\":\""+name+"\",\"provider_type\":\"OPENAI\",\"base_url\":\"https://8.8.8.8/v1\",\"timeouts\":{\"connect_ms\":1000,\"read_ms\":10000},\"priority\":5,\"weight\":10}"; }
    private static String modelBody(String channel,String code,boolean enabled) { return "{\"channel_id\":\""+channel+"\",\"model_id\":\""+code+"\",\"display_name\":\"Model\",\"input_price\":\"1\",\"output_price\":\"2\",\"price_unit\":1000000,\"currency\":\"USD\",\"tokenizer_family\":\"cl100k_base\",\"context_window\":8192,\"max_output_tokens\":1024,\"support_stream\":true,\"support_system_message\":true,\"support_temperature\":false,\"support_top_p\":false,\"support_stop\":false,\"enabled\":"+enabled+"}"; }
    private static String keyBody(String name,String secret) { return "{\"name\":\""+name+"\",\"secret_source\":\"INLINE_ENCRYPTED\",\"secret_value\":\""+secret+"\",\"priority\":5,\"weight\":10,\"enabled\":true}"; }
    private static String routeBody(String channel,String model,int weight,Integer version) { return "{\"channel_id\":\""+channel+"\",\"upstream_model_id\":\""+model+"\",\"priority\":10,\"weight\":"+weight+",\"enabled\":true"+(version==null?"":",\"version\":"+version)+"}"; }
    private static String keys(String channel) { return "/admin/channels/"+channel+"/credentials"; }
    private static String routes(String model) { return "/admin/virtual-models/"+model+"/routes"; }
    private ResultActions call(MockHttpServletRequestBuilder request,String body) throws Exception {
        request.header("x-test-role",Roles.SYSTEM_ADMIN);
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(request);
    }
    private static JsonNode data(ResultActions result) throws Exception { return ProtocolJson.protocol().readTree(result.andReturn().getResponse().getContentAsString()).path("data"); }
    private static String id(ResultActions result) throws Exception { result.andExpect(status().is2xxSuccessful()).andExpect(jsonPath("$.data.id").isString()); return data(result).path("id").asText(); }
}