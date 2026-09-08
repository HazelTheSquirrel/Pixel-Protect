package de.pixelprotect.storage;

import com.google.gson.Gson;
import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Single-writer durable audit storage. All JDBC access is serialized on one dedicated executor. */
public class Database implements AutoCloseable {
    protected static final int SCHEMA_VERSION = 8;
    protected static final Gson GSON = new Gson();

    protected final Path file;
    protected final Path overflowFile;
    protected final Logger logger;
    protected final int batchSize;
    protected final long flushIntervalMillis;
    protected final BlockingQueue<AuditEntry> queue;
    protected final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "PixelProtect-Database");
        thread.setDaemon(true);
        return thread;
    });

    protected Connection connection;
    protected volatile boolean running;

    public Database(Path file, int queueCapacity, int batchSize, long flushIntervalMillis, Logger logger) {
        this.file = file;
        this.overflowFile = file.resolveSibling(file.getFileName() + ".overflow.jsonl");
        this.logger = logger;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMillis = Math.max(25L, flushIntervalMillis);
    }

    public void open() throws SQLException, IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        configureSqlite(connection);
        migrate(connection);
        running = true;
        replayOverflow();
        executor.scheduleAtFixedRate(this::flushQueue, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private static void configureSqlite(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    protected void migrate(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS pixelprotect_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            int version = schemaVersion(connection);

            if (version < 1) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS audit (id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,actor_uuid TEXT,actor_name TEXT NOT NULL,action TEXT NOT NULL,before_data TEXT NOT NULL,after_data TEXT NOT NULL,before_inventory BLOB,after_inventory BLOB)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_location_time ON audit(world,x,z,time DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit(actor_uuid,time DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_action_time ON audit(action,time DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit(time)");
                version = 1;
            }
            if (version < 2) {
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_y_time ON audit(world,y,time DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_action_time ON audit(world,action,time DESC)");
                version = 2;
            }
            if (version < 3) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_jobs (id TEXT PRIMARY KEY,status TEXT NOT NULL,total INTEGER NOT NULL,processed INTEGER NOT NULL DEFAULT 0,applied INTEGER NOT NULL DEFAULT 0,skipped INTEGER NOT NULL DEFAULT 0,error TEXT,created_at INTEGER NOT NULL,finished_at INTEGER)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_job_entries (job_id TEXT NOT NULL,audit_id INTEGER NOT NULL,applied INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(job_id,audit_id),FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rollback_job_entries_applied ON rollback_job_entries(job_id,applied)");
                version = 3;
            }
            if (version < 4) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_restores (id TEXT PRIMARY KEY,source_job_id TEXT NOT NULL,status TEXT NOT NULL,applied INTEGER NOT NULL DEFAULT 0,skipped INTEGER NOT NULL DEFAULT 0,error TEXT,created_at INTEGER NOT NULL,finished_at INTEGER,FOREIGN KEY(source_job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rollback_restores_source ON rollback_restores(source_job_id,created_at DESC)");
                version = 4;
            }
            if (version < 5) {
                addColumnIfMissing(statement, "audit", "before_block_entity", "TEXT");
                addColumnIfMissing(statement, "audit", "after_block_entity", "TEXT");
                version = 5;
            }
            if (version < 6) {
                addColumnIfMissing(statement, "audit", "transaction_id", "TEXT");
                addColumnIfMissing(statement, "audit", "sequence", "INTEGER NOT NULL DEFAULT 0");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_transaction ON audit(transaction_id,sequence,id)");
                version = 6;
            }
            if (version < 7) {
                createExtendedAuditTables(statement);
                version = 7;
            }
            if (version < 8) {
                addColumnIfMissing(statement, "audit", "details", "TEXT");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_time_id ON audit(world,time DESC,id DESC)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_xyz_time ON audit(world,x,y,z,time DESC,id DESC)");
                version = 8;
            }
            if (version != SCHEMA_VERSION) throw new SQLException("Unsupported PixelProtect schema version: " + version);

            statement.executeUpdate("UPDATE rollback_jobs SET status='FAILED',error='Server restarted while rollback was running.',finished_at=" + System.currentTimeMillis() + " WHERE status='RUNNING'");
            try (PreparedStatement update = connection.prepareStatement("INSERT INTO pixelprotect_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
                update.setString(1, "schema_version");
                update.setString(2, Integer.toString(version));
                update.executeUpdate();
            }
        }
    }

    private static void createExtendedAuditTables(Statement statement) throws SQLException {
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS entity_audit (id INTEGER PRIMARY KEY AUTOINCREMENT,audit_id INTEGER NOT NULL,world TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,actor_uuid TEXT,action TEXT NOT NULL,before_snapshot TEXT,after_snapshot TEXT,spawn_reason TEXT,remove_cause TEXT,transaction_id TEXT,sequence INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entity_audit_location_time ON entity_audit(world,x,y,z,audit_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entity_audit_actor ON entity_audit(actor_uuid,audit_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_entity_audit_transaction ON entity_audit(transaction_id,sequence,audit_id)");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS inventory_audit (id INTEGER PRIMARY KEY AUTOINCREMENT,audit_id INTEGER NOT NULL,world TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,actor_uuid TEXT,slot_diff TEXT NOT NULL,transaction_id TEXT,sequence INTEGER NOT NULL DEFAULT 0,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_location ON inventory_audit(world,x,y,z,audit_id)");
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_inventory_audit_transaction ON inventory_audit(transaction_id,sequence,audit_id)");
    }

    private int schemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM pixelprotect_meta WHERE key='schema_version'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Integer.parseInt(result.getString(1)) : 0;
            }
        }
    }

    protected static void addColumnIfMissing(Statement statement, String table, String column, String type) throws SQLException {
        try {
            statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException exception) {
            String message = exception.getMessage();
            if (message == null || (!message.toLowerCase(java.util.Locale.ROOT).contains("duplicate") && !message.toLowerCase(java.util.Locale.ROOT).contains("already exists"))) throw exception;
        }
    }

    public boolean record(AuditEntry entry) {
        if (!running || entry == null) return false;
        if (queue.offer(entry)) return true;
        return spool(entry);
    }

    protected boolean spool(AuditEntry entry) {
        try {
            Files.writeString(overflowFile, GSON.toJson(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Failed to spool audit record.", exception);
            return false;
        }
    }

    public CompletableFuture<List<AuditEntry>> query(AuditQuery query) {
        return executeAsync(() -> {
            flushQueue();
            return queryBlocking(query);
        });
    }

    public CompletableFuture<Long> count(AuditQuery query) {
        return executeAsync(() -> {
            flushQueue();
            return countBlocking(query);
        });
    }

    public CompletableFuture<List<AuditEntry>> query(UUID world, int x, int y, int z, int radius, long since, long until, String actor, int limit) {
        return query(new AuditQuery(world, x, y, z, radius, since, until, actor, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), limit));
    }

    protected List<AuditEntry> queryBlocking(AuditQuery query) throws SQLException {
        QuerySql built = buildQuery(query, false);
        List<Object> parameters = new ArrayList<>(built.parameters());
        parameters.add(query.limit());
        parameters.add(query.offset());
        try (PreparedStatement statement = connection.prepareStatement(built.sql() + " ORDER BY time DESC,id DESC LIMIT ? OFFSET ?")) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                List<AuditEntry> entries = new ArrayList<>();
                while (result.next()) entries.add(read(result));
                return entries;
            }
        }
    }

    protected long countBlocking(AuditQuery query) throws SQLException {
        QuerySql built = buildQuery(query, true);
        try (PreparedStatement statement = connection.prepareStatement(built.sql())) {
            bind(statement, built.parameters());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    protected QuerySql buildQuery(AuditQuery query, boolean count) {
        int radius = query.radius();
        long radiusSquared = (long) radius * radius;
        String select = count
                ? "SELECT COUNT(*) FROM audit WHERE "
                : "SELECT id,time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory,before_block_entity,after_block_entity,details,transaction_id,sequence FROM audit WHERE ";
        StringBuilder sql = new StringBuilder(select);
        sql.append("world=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND ((x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?))<=? AND time>=? AND time<=?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.world().toString());
        parameters.add((long) query.centerX() - radius);
        parameters.add((long) query.centerX() + radius);
        parameters.add((long) query.centerY() - radius);
        parameters.add((long) query.centerY() + radius);
        parameters.add((long) query.centerZ() - radius);
        parameters.add((long) query.centerZ() + radius);
        parameters.add(query.centerX());
        parameters.add(query.centerX());
        parameters.add(query.centerY());
        parameters.add(query.centerY());
        parameters.add(query.centerZ());
        parameters.add(query.centerZ());
        parameters.add(radiusSquared);
        parameters.add(query.since());
        parameters.add(query.until());
        if (query.actorName() != null && !query.actorName().isBlank()) {
            sql.append(" AND actor_name=?");
            parameters.add(query.actorName());
        }
        appendActions(sql, parameters, query.includeActions(), true);
        appendActions(sql, parameters, query.excludeActions(), false);
        appendBlocks(sql, parameters, query.includeBlocks(), false);
        appendBlocks(sql, parameters, query.excludeBlocks(), true);
        return new QuerySql(sql.toString(), parameters);
    }

    protected static void appendActions(StringBuilder sql, List<Object> parameters, java.util.Set<ActionType> actions, boolean include) {
        if (actions.isEmpty()) return;
        sql.append(include ? " AND action IN (" : " AND action NOT IN (");
        sql.append("?,".repeat(actions.size()));
        sql.setLength(sql.length() - 1);
        sql.append(')');
        actions.forEach(action -> parameters.add(action.name()));
    }

    protected static void appendBlocks(StringBuilder sql, List<Object> parameters, java.util.Set<String> blocks, boolean exclude) {
        for (String block : blocks) {
            sql.append(exclude ? " AND before_data NOT LIKE ? AND after_data NOT LIKE ?" : " AND (before_data LIKE ? OR after_data LIKE ?)");
            parameters.add(block + "%");
            parameters.add(block + "%");
        }
    }

    protected static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object value = parameters.get(index);
            if (value instanceof Integer integer) statement.setInt(index + 1, integer);
            else if (value instanceof Long longer) statement.setLong(index + 1, longer);
            else statement.setString(index + 1, value.toString());
        }
    }

    public CompletableFuture<Integer> purgeBefore(long cutoff) {
        return executeAsync(() -> {
            flushQueue();
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audit WHERE time < ? AND id NOT IN (SELECT audit_id FROM rollback_job_entries)")) {
                statement.setLong(1, cutoff);
                return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Long> count() {
        return executeAsync(() -> {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM audit")) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Long> failedRollbackCount() {
        return executeAsync(() -> {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM rollback_jobs WHERE status='FAILED'")) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Void> recordEntityAudit(EntityAuditRecord record) {
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO entity_audit(audit_id,world,x,y,z,actor_uuid,action,before_snapshot,after_snapshot,spawn_reason,remove_cause,transaction_id,sequence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, record.auditId());
                statement.setString(2, record.world().toString());
                statement.setDouble(3, record.x());
                statement.setDouble(4, record.y());
                statement.setDouble(5, record.z());
                if (record.actor() == null) statement.setNull(6, Types.VARCHAR); else statement.setString(6, record.actor().toString());
                statement.setString(7, record.action().name());
                statement.setString(8, record.beforeSnapshot());
                statement.setString(9, record.afterSnapshot());
                statement.setString(10, record.spawnReason());
                statement.setString(11, record.removeCause());
                statement.setString(12, record.transactionId() == null ? null : record.transactionId().toString());
                statement.setLong(13, record.sequence());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> recordInventoryAudit(InventoryAuditRecord record) {
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO inventory_audit(audit_id,world,x,y,z,actor_uuid,slot_diff,transaction_id,sequence) VALUES(?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, record.auditId());
                statement.setString(2, record.world().toString());
                statement.setInt(3, record.x());
                statement.setInt(4, record.y());
                statement.setInt(5, record.z());
                if (record.actor() == null) statement.setNull(6, Types.VARCHAR); else statement.setString(6, record.actor().toString());
                statement.setString(7, record.slotDiff());
                statement.setString(8, record.transactionId() == null ? null : record.transactionId().toString());
                statement.setLong(9, record.sequence());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> createRollbackJob(UUID id, List<AuditEntry> entries) {
        List<AuditEntry> snapshot = List.copyOf(entries);
        return executeAsync(() -> {
            flushQueue();
            connection.setAutoCommit(false);
            try (PreparedStatement job = connection.prepareStatement("INSERT INTO rollback_jobs(id,status,total,created_at) VALUES(?,?,?,?)"); PreparedStatement entry = connection.prepareStatement("INSERT INTO rollback_job_entries(job_id,audit_id) VALUES(?,?)")) {
                job.setString(1, id.toString());
                job.setString(2, "RUNNING");
                job.setInt(3, snapshot.size());
                job.setLong(4, System.currentTimeMillis());
                job.executeUpdate();
                for (AuditEntry auditEntry : snapshot) {
                    entry.setString(1, id.toString());
                    entry.setLong(2, auditEntry.id());
                    entry.addBatch();
                }
                entry.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
            return null;
        });
    }

    public CompletableFuture<Void> updateRollbackJob(UUID id, String status, int processed, int applied, int skipped, String error) {
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE rollback_jobs SET status=?,processed=?,applied=?,skipped=?,error=?,finished_at=? WHERE id=?")) {
                statement.setString(1, status);
                statement.setInt(2, processed);
                statement.setInt(3, applied);
                statement.setInt(4, skipped);
                if (error == null) statement.setNull(5, Types.VARCHAR); else statement.setString(5, error);
                if ("RUNNING".equals(status)) statement.setNull(6, Types.BIGINT); else statement.setLong(6, System.currentTimeMillis());
                statement.setString(7, id.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> markRollbackApplied(UUID id, List<Long> ids) {
        return executeAsync(() -> {
            if (ids.isEmpty()) return null;
            try (PreparedStatement statement = connection.prepareStatement("UPDATE rollback_job_entries SET applied=1 WHERE job_id=? AND audit_id=?")) {
                for (long auditId : ids) {
                    statement.setString(1, id.toString());
                    statement.setLong(2, auditId);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    public CompletableFuture<RollbackJobRecord> rollbackJob(UUID id) {
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id,status,total,processed,applied,skipped,error,created_at,finished_at FROM rollback_jobs WHERE id=?")) {
                statement.setString(1, id.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return null;
                    long finished = result.getLong(9);
                    return new RollbackJobRecord(UUID.fromString(result.getString(1)), result.getString(2), result.getInt(3), result.getInt(4), result.getInt(5), result.getInt(6), result.getString(7), result.getLong(8), result.wasNull() ? 0L : finished);
                }
            }
        });
    }

    public CompletableFuture<List<AuditEntry>> appliedRollbackEntries(UUID id) {
        return executeAsync(() -> {
            flushQueue();
            List<AuditEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT a.id,a.time,a.world,a.x,a.y,a.z,a.actor_uuid,a.actor_name,a.action,a.before_data,a.after_data,a.before_inventory,a.after_inventory,a.before_block_entity,a.after_block_entity,a.details,a.transaction_id,a.sequence FROM rollback_job_entries j JOIN audit a ON a.id=j.audit_id WHERE j.job_id=? AND j.applied=1 ORDER BY a.id ASC")) {
                statement.setString(1, id.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) entries.add(read(result));
                }
            }
            return entries;
        });
    }

    public CompletableFuture<UUID> createRestore(UUID source) {
        UUID id = UUID.randomUUID();
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO rollback_restores(id,source_job_id,status,created_at) VALUES(?,?,?,?)")) {
                statement.setString(1, id.toString());
                statement.setString(2, source.toString());
                statement.setString(3, "RUNNING");
                statement.setLong(4, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return id;
        });
    }

    public CompletableFuture<Void> updateRestore(UUID id, String status, int applied, int skipped, String error) {
        return executeAsync(() -> {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE rollback_restores SET status=?,applied=?,skipped=?,error=?,finished_at=? WHERE id=?")) {
                statement.setString(1, status);
                statement.setInt(2, applied);
                statement.setInt(3, skipped);
                if (error == null) statement.setNull(4, Types.VARCHAR); else statement.setString(4, error);
                if ("RUNNING".equals(status)) statement.setNull(5, Types.BIGINT); else statement.setLong(5, System.currentTimeMillis());
                statement.setString(6, id.toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    protected void flushQueue() {
        if (connection == null || queue.isEmpty()) return;
        List<AuditEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return;
        try {
            insertBatch(batch);
        } catch (SQLException exception) {
            for (AuditEntry entry : batch) {
                if (!spool(entry)) logger.log(Level.SEVERE, "Audit record lost after database failure: " + entry.id());
            }
            logger.log(Level.SEVERE, "Audit batch insert failed; records were redirected to overflow storage.", exception);
        }
        replayOverflow();
    }

    protected void replayOverflow() {
        if (connection == null || !Files.exists(overflowFile)) return;
        Path replay = overflowFile.resolveSibling(overflowFile.getFileName() + ".replay");
        try {
            Files.move(overflowFile, replay, StandardCopyOption.REPLACE_EXISTING);
            List<AuditEntry> batch = new ArrayList<>(batchSize);
            List<String> remaining = new ArrayList<>();
            try (var lines = Files.lines(replay, StandardCharsets.UTF_8)) {
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (line.isBlank()) continue;
                    try {
                        AuditEntry entry = GSON.fromJson(line, AuditEntry.class);
                        if (entry == null) throw new IllegalArgumentException("null audit record");
                        batch.add(entry);
                        if (batch.size() >= batchSize) {
                            insertBatch(batch);
                            batch.clear();
                        }
                    } catch (RuntimeException | SQLException exception) {
                        remaining.add(line);
                    }
                }
            }
            if (!batch.isEmpty()) {
                try {
                    insertBatch(batch);
                } catch (SQLException exception) {
                    for (AuditEntry entry : batch) remaining.add(GSON.toJson(entry));
                }
            }
            Files.deleteIfExists(replay);
            if (!remaining.isEmpty()) Files.write(overflowFile, remaining, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException exception) {
            try {
                if (Files.exists(replay)) Files.move(replay, overflowFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException restoreFailure) {
                logger.log(Level.SEVERE, "Overflow recovery failed; replay file remains at " + replay, restoreFailure);
            }
        }
    }

    protected void insertBatch(List<AuditEntry> entries) throws SQLException {
        if (entries.isEmpty()) return;
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit(time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory,before_block_entity,after_block_entity,details,transaction_id,sequence) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (AuditEntry entry : entries) {
                int index = 1;
                statement.setLong(index++, entry.time());
                statement.setString(index++, entry.world().toString());
                statement.setInt(index++, entry.x());
                statement.setInt(index++, entry.y());
                statement.setInt(index++, entry.z());
                if (entry.actor() == null) statement.setNull(index++, Types.VARCHAR); else statement.setString(index++, entry.actor().toString());
                statement.setString(index++, entry.actorName());
                statement.setString(index++, entry.action().name());
                statement.setString(index++, entry.beforeData());
                statement.setString(index++, entry.afterData());
                statement.setBytes(index++, entry.beforeInventoryCopy());
                statement.setBytes(index++, entry.afterInventoryCopy());
                statement.setString(index++, entry.beforeBlockEntity());
                statement.setString(index++, entry.afterBlockEntity());
                statement.setString(index++, entry.details());
                if (entry.transactionId() == null) statement.setNull(index++, Types.VARCHAR); else statement.setString(index++, entry.transactionId().toString());
                statement.setLong(index, entry.sequence());
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    protected AuditEntry read(ResultSet result) throws SQLException {
        String actor = result.getString("actor_uuid");
        String transaction = result.getString("transaction_id");
        return new AuditEntry(
                result.getLong("id"), result.getLong("time"), UUID.fromString(result.getString("world")),
                result.getInt("x"), result.getInt("y"), result.getInt("z"), actor == null ? null : UUID.fromString(actor),
                result.getString("actor_name"), ActionType.valueOf(result.getString("action")), result.getString("before_data"),
                result.getString("after_data"), result.getBytes("before_inventory"), result.getBytes("after_inventory"),
                result.getString("before_block_entity"), result.getString("after_block_entity"), result.getString("details"),
                transaction == null ? null : UUID.fromString(transaction), result.getLong("sequence"));
    }

    protected <T> CompletableFuture<T> executeAsync(SqlSupplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public int queueSize() { return queue.size(); }
    public int schemaVersion() { return SCHEMA_VERSION; }

    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        if (connection != null) {
            try {
                while (!queue.isEmpty()) flushQueue();
                replayOverflow();
                connection.close();
            } catch (SQLException exception) {
                logger.log(Level.SEVERE, "Database close failed.", exception);
            }
        }
    }

    protected record QuerySql(String sql, List<Object> parameters) {}
    @FunctionalInterface protected interface SqlSupplier<T> { T get() throws Exception; }

    public record RollbackJobRecord(UUID id, String status, int total, int processed, int applied, int skipped, String error, long createdAt, long finishedAt) {}
    public record EntityAuditRecord(long auditId, UUID world, double x, double y, double z, UUID actor, ActionType action, String beforeSnapshot, String afterSnapshot, String spawnReason, String removeCause, UUID transactionId, long sequence) {}
    public record InventoryAuditRecord(long auditId, UUID world, int x, int y, int z, UUID actor, String slotDiff, UUID transactionId, long sequence) {}
}
