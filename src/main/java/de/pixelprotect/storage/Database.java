package de.pixelprotect.storage;

import de.pixelprotect.model.ActionType;
import de.pixelprotect.model.AuditEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class Database implements AutoCloseable {
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
        if (parent != null) {
            Files.createDirectories(parent);
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
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
        }
        running = true;
        executor.scheduleAtFixedRate(this::flushQueue, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public boolean record(AuditEntry entry) {
        if (!running) {
            return false;
        }
        if (!queue.offer(entry)) {
            logger.severe("Audit queue is full; refusing to silently drop an audit record.");
            return false;
        }
        return true;
    }

    public CompletableFuture<List<AuditEntry>> query(UUID world, int centerX, int centerY, int centerZ,
                                                       int radius, long since, long until, String actorName,
                                                       int limit) {
        final CompletableFuture<List<AuditEntry>> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushQueue();
                future.complete(queryBlocking(world, centerX, centerY, centerZ, radius, since, until, actorName, limit));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private List<AuditEntry> queryBlocking(UUID world, int centerX, int centerY, int centerZ,
                                           int radius, long since, long until, String actorName,
                                           int limit) throws SQLException {
        final int safeRadius = Math.max(0, radius);
        final int safeLimit = Math.max(1, limit);
        final long radiusSquared = (long) safeRadius * safeRadius;
        final StringBuilder sql = new StringBuilder("""
                SELECT id,time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory
                FROM audit
                WHERE world = ?
                  AND x BETWEEN ? AND ?
                  AND y BETWEEN ? AND ?
                  AND z BETWEEN ? AND ?
                  AND ((CAST(x AS INTEGER) - ?) * (CAST(x AS INTEGER) - ?)
                     + (CAST(y AS INTEGER) - ?) * (CAST(y AS INTEGER) - ?)
                     + (CAST(z AS INTEGER) - ?) * (CAST(z AS INTEGER) - ?)) <= ?
                  AND time >= ?
                  AND time <= ?
                """);
        if (actorName != null && !actorName.isBlank()) {
            sql.append(" AND actor_name = ?");
        }
        sql.append(" ORDER BY time DESC, id DESC LIMIT ?");

        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, world.toString());
            statement.setInt(index++, centerX - safeRadius);
            statement.setInt(index++, centerX + safeRadius);
            statement.setInt(index++, centerY - safeRadius);
            statement.setInt(index++, centerY + safeRadius);
            statement.setInt(index++, centerZ - safeRadius);
            statement.setInt(index++, centerZ + safeRadius);
            statement.setInt(index++, centerX);
            statement.setInt(index++, centerX);
            statement.setInt(index++, centerY);
            statement.setInt(index++, centerY);
            statement.setInt(index++, centerZ);
            statement.setInt(index++, centerZ);
            statement.setLong(index++, radiusSquared);
            statement.setLong(index++, since);
            statement.setLong(index++, until);
            if (actorName != null && !actorName.isBlank()) {
                statement.setString(index++, actorName);
            }
            statement.setInt(index, safeLimit);
            try (ResultSet result = statement.executeQuery()) {
                final List<AuditEntry> entries = new ArrayList<>();
                while (result.next()) {
                    entries.add(read(result));
                }
                return entries;
            }
        }
    }

    public CompletableFuture<Integer> purgeBefore(long cutoff) {
        final CompletableFuture<Integer> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushQueue();
                try (PreparedStatement statement = connection.prepareStatement("DELETE FROM audit WHERE time < ?")) {
                    statement.setLong(1, cutoff);
                    final int count = statement.executeUpdate();
                    future.complete(count);
                }
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public CompletableFuture<Long> count() {
        final CompletableFuture<Long> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                flushQueue();
                try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM audit")) {
                    future.complete(result.next() ? result.getLong(1) : 0L);
                }
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private AuditEntry read(ResultSet result) throws SQLException {
        final String actorUuid = result.getString("actor_uuid");
        return new AuditEntry(
                result.getLong("id"),
                result.getLong("time"),
                UUID.fromString(result.getString("world")),
                result.getInt("x"),
                result.getInt("y"),
                result.getInt("z"),
                actorUuid == null ? null : UUID.fromString(actorUuid),
                result.getString("actor_name"),
                ActionType.valueOf(result.getString("action")),
                result.getString("before_data"),
                result.getString("after_data"),
                result.getBytes("before_inventory"),
                result.getBytes("after_inventory")
        );
    }

    private void flushQueue() {
        if (queue.isEmpty() || connection == null) {
            return;
        }
        final List<AuditEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        try {
            insertBatch(batch);
        } catch (SQLException exception) {
            logger.severe("Failed to persist audit batch: " + exception.getMessage());
            for (AuditEntry entry : batch) {
                if (!queue.offer(entry)) {
                    logger.severe("Audit queue overflow while retrying a failed database batch.");
                }
            }
        }
    }

    private void insertBatch(List<AuditEntry> entries) throws SQLException {
        if (entries.isEmpty()) {
            return;
        }
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO audit(time,world,x,y,z,actor_uuid,actor_name,action,before_data,after_data,before_inventory,after_inventory)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (AuditEntry entry : entries) {
                int index = 1;
                statement.setLong(index++, entry.time());
                statement.setString(index++, entry.world().toString());
                statement.setInt(index++, entry.x());
                statement.setInt(index++, entry.y());
                statement.setInt(index++, entry.z());
                if (entry.actor() == null) {
                    statement.setNull(index++, java.sql.Types.VARCHAR);
                } else {
                    statement.setString(index++, entry.actor().toString());
                }
                statement.setString(index++, entry.actorName());
                statement.setString(index++, entry.action().name());
                statement.setString(index++, entry.beforeData());
                statement.setString(index++, entry.afterData());
                statement.setBytes(index++, entry.beforeInventory());
                statement.setBytes(index, entry.afterInventory());
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

    @Override
    public void close() {
        running = false;
        executor.execute(this::flushQueue);
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException exception) {
            logger.warning("Failed to close SQLite connection: " + exception.getMessage());
        }
    }
}
