package com.lightai.admin.access;

import com.lightai.admin.audit.AuditService;
import com.lightai.admin.draft.DraftWriteService;
import com.lightai.storage.audit.AuditRecord;
import com.lightai.storage.audit.AuditRepository;
import com.lightai.storage.draft.DraftChangeRecord;
import com.lightai.storage.draft.DraftChangeRepository;
import com.lightai.storage.draft.DraftStateRepository;
import com.lightai.storage.draft.DraftStateSnapshot;
import com.lightai.storage.draft.DraftStatus;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * BE-P03 测试夹具：JDBC 对象动态代理（连接不经真实数据库，仓储使用内存实现）、
 * 无外部依赖的事务管理器、内存草稿状态/差异/审计仓储。
 * 服务事务语义在此层面验证；真实 PostgreSQL 行锁与原子性证据随 DB-P02 联调补充。
 */
public final class AccessTestSupport {

    public static Connection proxyConnection() {
        return (Connection) Proxy.newProxyInstance(AccessTestSupport.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    public static DataSource proxyDataSource() {
        return (DataSource) Proxy.newProxyInstance(AccessTestSupport.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> method.getName().equals("getConnection")
                        ? proxyConnection() : defaultValue(method.getReturnType()));
    }

    public static PlatformTransactionManager noopTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
            }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (!type.isPrimitive()) {
            return null;
        }
        return null;
    }

    /** 内存草稿锁状态：单例、revision 与 change_count 递增、PUBLISHING 可模拟。 */
    public static final class FakeDraftStateRepository implements DraftStateRepository {
        public DraftStatus status = DraftStatus.EDITABLE;
        public long draftRevision = 0;
        public int changeCount = 0;

        @Override
        public Optional<DraftStateSnapshot> find(Connection connection) {
            return Optional.of(new DraftStateSnapshot(0, draftRevision, status, null, changeCount));
        }

        @Override
        public DraftStateSnapshot lock(Connection connection) {
            if (status == DraftStatus.PUBLISHING) {
                throw new IllegalStateException("PUBLISHING");
            }
            return new DraftStateSnapshot(0, draftRevision, status, null, changeCount);
        }

        @Override
        public DraftStateSnapshot bumpRevision(Connection connection, int changeCountDelta) {
            this.draftRevision++;
            this.changeCount += changeCountDelta;
            return new DraftStateSnapshot(0, draftRevision, status, null, changeCount);
        }

        public void publish() {
            this.status = DraftStatus.PUBLISHING;
        }
    }

    /** 内存草稿差异：U(entity_type, entity_id) 覆盖语义。 */
    public static final class FakeDraftChangeRepository implements DraftChangeRepository {
        public final List<DraftChangeRecord> records = new ArrayList<>();
        private final Map<String, DraftChangeRecord> byEntity = new HashMap<>();

        @Override
        public boolean upsert(Connection connection, DraftChangeRecord record) {
            boolean inserted = byEntity.put(record.entityType() + "/" + record.entityId(), record) == null;
            records.add(record);
            return inserted;
        }

        @Override
        public boolean existsByEntity(Connection connection, String entityType, UUID entityId) {
            return byEntity.containsKey(entityType + "/" + entityId);
        }

        @Override
        public Set<UUID> findExistingEntityIds(Connection connection, String entityType, java.util.Collection<UUID> entityIds) {
            Set<UUID> result = new HashSet<>();
            for (UUID id : entityIds) {
                if (byEntity.containsKey(entityType + "/" + id)) {
                    result.add(id);
                }
            }
            return result;
        }
    }

    /** 内存审计：记录成功/失败写入，供 request_id 关联与脱敏扫描断言。 */
    public static final class FakeAuditRepository implements AuditRepository {
        public final List<AuditRecord> records = new ArrayList<>();

        @Override
        public void insert(Connection connection, AuditRecord record) {
            if (record.errorSummary() != null && record.errorSummary().contains("sk-secret")) {
                throw new IllegalStateException("secret leak");
            }
            records.add(record);
        }
    }

    public static final class DraftFixture {
        public final DraftWriteService draftWriteService;
        public final FakeDraftStateRepository state;
        public final FakeDraftChangeRepository changes;
        public final com.lightai.admin.audit.AuditService audit;

        public DraftFixture(DraftWriteService draftWriteService, FakeDraftStateRepository state,
                            FakeDraftChangeRepository changes, com.lightai.admin.audit.AuditService audit) {
            this.draftWriteService = draftWriteService;
            this.state = state;
            this.changes = changes;
            this.audit = audit;
        }
    }

    public static DraftFixture draftFixture(AuditService auditService) {
        FakeDraftStateRepository state = new FakeDraftStateRepository();
        FakeDraftChangeRepository changes = new FakeDraftChangeRepository();
        DraftWriteService service = new DraftWriteService(proxyDataSource(), noopTransactionManager(),
                state, changes, auditService);
        return new DraftFixture(service, state, changes, auditService);
    }

    public static AuditService auditService(FakeAuditRepository repository) {
        return new AuditService(repository, proxyDataSource(), noopTransactionManager(), null);
    }

    /** 内存检测记录仓储。 */
    public static final class FakeCheckRecordRepository implements com.lightai.storage.check.CheckRecordRepository {
        public final java.util.List<com.lightai.storage.check.CheckRecord> records = new java.util.ArrayList<>();

        @Override
        public void insert(Connection connection, com.lightai.storage.check.CheckRecord record) {
            records.add(record);
        }

        @Override
        public java.util.Optional<com.lightai.storage.check.CheckRecord> find(Connection connection, java.util.UUID id) {
            return records.stream().filter(record -> record.id().equals(id)).findFirst();
        }

        @Override
        public java.util.List<com.lightai.storage.check.CheckRecord> findLatestByTarget(
                Connection connection, java.util.UUID targetId, int limit) {
            return records.stream().filter(record -> record.targetId().equals(targetId))
                    .sorted(java.util.Comparator.comparing(
                            com.lightai.storage.check.CheckRecord::startedAt).reversed())
                    .limit(limit)
                    .toList();
        }
    }

    /** 运行状态空实现：列表/详情组合用；检测回写为 no-op。 */
    public static final class EmptyRuntimeStateRepository
            implements com.lightai.storage.access.ObjectRuntimeStateRepository {
        @Override
        public java.util.Map<UUID, RuntimeStateRow> find(Connection connection, String entityType,
                                                         java.util.Collection<UUID> entityIds) {
            return java.util.Map.of();
        }

        @Override
        public void upsertAfterCheck(Connection connection, String entityType, UUID entityId,
                                     String connectionStatus, String healthStatus, boolean success,
                                     String errorCode, String errorSummary) {
        }
    }

    private AccessTestSupport() {
    }
}
