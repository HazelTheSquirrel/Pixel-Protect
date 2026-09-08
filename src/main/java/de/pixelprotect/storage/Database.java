package de.pixelprotect.storage;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;
import de.pixelprotect.model.AuditQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import java.util.logging.Logger;

public final class Database implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private final Path file;
    private final Logger logger;
    private final int batchSize;
    private final long flushIntervalMillis;
    private final BlockingQueue<AuditEntry> queue;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread thread = new Thread(r, "PixelProtect-Database");
        thread.setDaemon(true);
        return thread;
    });
    private Connection connection;
    private volatile boolean running;

    public Database(Path file, int queueCapacity, int batchSize, long flushIntervalMillis, Logger logger) {
        this.file = file;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.flushIntervalMillis = Math.max(25L, flushIntervalMillis);
        this.logger = logger;
    }

    public void open() throws SQLException, IOException {
        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
            migrate(statement);
        }
        running = true;
        executor.scheduleAtFixedRate(this::flushQueue, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void migrate(java.sql.Statement statement) throws SQLException {
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS pixelprotect_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        int version = 0;
        try (var result = statement.executeQuery("SELECT value FROM pixelprotect_meta WHERE key='schema_version'")) {
            if (result.next()) version = Integer.parseInt(result.getString(1));
        }
        if (version < 1) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS audit (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        actor_uuid TEXT,
                        actor_name TEXT NOT NULL,
                        action TEXT NOT NULL,
                        before_data TEXT NOT NULL,
                        after_data TEXT NOT NULL,
                        before_inventory BLOB,
                        after_inventory BLOB
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_location_time ON audit(world, x, z, time DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_actor_time ON audit(actor_uuid, time DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_action_time ON audit(action, time DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit(time)");
            version = 1;
        }
        if (version < 2) {
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_y_time ON audit(world, y, time DESC)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_audit_world_action_time ON audit(world, action, time DESC)");
            version = 2;
        }
        try (PreparedStatement update = connection.prepareStatement("INSERT INTO pixelprotect_meta(key,value) VALUES('schema_version',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            update.setString(1, Integer.toString(version));
            update.executeUpdate();
        }
        if (version != SCHEMA_VERSION) throw new SQLException("Unsupported PixelProtect schema version: " + version);
    }

    public boolean record(AuditEntry entry) {
        if (!running || !queue.offer(entry)) {
            if (running) logger.severe("Audit queue is full; refusing to silently drop an audit record.");
            return false;
        }
        return true;
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

    public CompletableFuture<List<AuditEntry>> query(UUID world, int centerX, int centerY, int centerZ,
                                                       int radius, long since, long until, String actorName, int limit) {
        return query(new AuditQuery(world, centerX, centerY, centerZ, radius, since, until, actorName,
                java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), java.util.Set.of(), limit));
    }

    private List<AuditEntry> queryBlocking(AuditQuery query) throws SQLException {
        final QuerySql built = buildQuery(query, false);
        final String sql = built.sql() + " ORDER BY time DESC,id DESC LIMIT ?";
        final List<Object> params = new ArrayList<>(built.params());
        params.add(query.limit());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet result = statement.executeQuery()) {
                final List<AuditEntry> entries = new ArrayList<>();
                while (result.next()) entries.add(read(result));
                return entries;
            }
        }
    }

    private long countBlocking(AuditQuery query) throws SQLException {
        final QuerySql built = buildQuery(query, true);
        try (PreparedStatement statement = connection.prepareStatement(built.sql())) {
            bind(statement, built.params());
            try (ResultSet result = statement.executeQuery()) return result.next() ? result.getLong(1) : 0L;
        }
    }

    private QuerySql buildQuery(AuditQuery query, boolean count) {
        final int radius = query.radius();
        final long radiusSquared = (long) radius * radius;
        final StringBuilder sql = new StringBuilder(count ? "SELECT COUNT(*) FROM audit WHERE " : "SELECT id,time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory FROM audit WHERE ");
        sql.append("world=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
        sql.append(" AND ((x-?)*(x-?)+(y-?)*(y-?)+(z-?)*(z-?)) <= ? AND time>=? AND time<=?");
        final List<Object> params = new ArrayList<>();
        params.add(query.world().toString());
        params.add(query.centerX() - radius); params.add(query.centerX() + radius);
        params.add(query.centerY() - radius); params.add(query.centerY() + radius);
        params.add(query.centerZ() - radius); params.add(query.centerZ() + radius);
        params.add(query.centerX()); params.add(query.centerX()); params.add(query.centerY()); params.add(query.centerY());
        params.add(query.centerZ()); params.add(query.centerZ()); params.add(radiusSquared);
        params.add(query.since()); params.add(query.until());
        if (query.actorName() != null && !query.actorName().isBlank()) { sql.append(" AND actor_name=?"); params.add(query.actorName()); }
        appendActions(sql, params, query.includeActions(), true);
        appendActions(sql, params, query.excludeActions(), false);
        appendBlocks(sql, params, query.includeBlocks(), false);
        appendBlocks(sql, params, query.excludeBlocks(), true);
        return new QuerySql(sql.toString(), params);
    }

    private static void appendActions(StringBuilder sql, List<Object> params, java.util.Set<ActionType> actions, boolean include) {
        if (actions.isEmpty()) return;
        sql.append(include ? " AND action IN (" : " AND action NOT IN (");
        sql.append("?,".repeat(actions.size()));
        sql.setLength(sql.length() - 1);
        sql.append(')');
        actions.forEach(action -> params.add(action.name()));
    }

    private static void appendBlocks(StringBuilder sql, List<Object> params, java.util.Set<String> blocks, boolean exclude) {
        for (String block : blocks) {
            sql.append(exclude ? " AND before_data NOT LIKE ? AND after_data NOT LIKE ?" : " AND (before_data LIKE ? OR after_data LIKE ?)");
            params.add(block + "%"); params.add(block + "%");
        }
    }

    private static void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) setParameter(statement, i + 1, params.get(i));
    }

    private static void setParameter(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Integer integer) statement.setInt(index, integer);
        else if (value instanceof Long number) statement.setLong(index, number);
        else statement.setString(index, value.toString());
    }

    public CompletableFuture<Integer> purgeBefore(long cutoff) {
        return executeAsync(() -> {
            flushQueue();
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audit WHERE time < ?")) {
                statement.setLong(1, cutoff); return statement.executeUpdate();
            }
        });
    }

    public CompletableFuture<Long> count() {
        return executeAsync(() -> {
            flushQueue();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM audit")) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    private <T> CompletableFuture<T> executeAsync(SqlSupplier<T> supplier) {
        final CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try { future.complete(supplier.get()); }
            catch (Throwable throwable) { future.completeExceptionally(throwable); }
        });
        return future;
    }

    private AuditEntry read(ResultSet result) throws SQLException {
        final String actorUuid = result.getString("actor_uuid");
        return new AuditEntry(result.getLong("id"), result.getLong("time"), UUID.fromString(result.getString("world")),
                result.getInt("x"), result.getInt("y"), result.getInt("z"), actorUuid == null ? null : UUID.fromString(actorUuid),
                result.getString("actor_name"), ActionType.valueOf(result.getString("action")), result.getString("before_data"),
                result.getString("after_data"), result.getBytes("before_inventory"), result.getBytes("after_inventory"));
    }

    private void flushQueue() {
        if (queue.isEmpty() || connection == null) return;
        final List<AuditEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        try { insertBatch(batch); }
        catch (SQLException exception) {
            logger.severe("Failed to persist audit batch: " + exception.getMessage());
            for (AuditEntry entry : batch) if (!queue.offer(entry)) logger.severe("Audit queue overflow while retrying a failed database batch.");
        }
    }

    private void insertBatch(List<AuditEntry> entries) throws SQLException {
        if (entries.isEmpty()) return;
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit(time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            for (AuditEntry entry : entries) {
                int i = 1;
                statement.setLong(i++, entry.time()); statement.setString(i++, entry.world().toString());
                statement.setInt(i++, entry.x()); statement.setInt(i++, entry.y()); statement.setInt(i++, entry.z());
                if (entry.actor() == null) statement.setNull(i++, Types.VARCHAR); else statement.setString(i++, entry.actor().toString());
                statement.setString(i++, entry.actorName()); statement.setString(i++, entry.action().name());
                statement.setString(i++, entry.beforeData()); statement.setString(i++, entry.afterData());
                statement.setBytes(i++, entry.beforeInventory()); statement.setBytes(i, entry.afterInventory()); statement.addBatch();
            }
            statement.executeBatch(); connection.commit();
        } catch (SQLException exception) { connection.rollback(); throw exception; }
        finally { connection.setAutoCommit(true); }
    }

    @Override public void close() {
        running = false;
        if (!executor.isShutdown()) executor.execute(this::flushQueue);
        executor.shutdown();
        try { executor.awaitTermination(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); executor.shutdownNow(); }
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException exception) { logger.warning("Failed to close SQLite connection: " + exception.getMessage()); }
    }

    private record QuerySql(String sql, List<Object> params) {}
    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
}
