package de.pixelprotect.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** MySQL/MariaDB JDBC backend using the project's HikariCP pool. */
public final class MySqlDatabase extends AsyncOverflowDatabase {
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int maximumPoolSize;
    private final int minimumIdle;
    private final long connectionTimeoutMillis;
    private final long leakDetectionMillis;
    private HikariDataSource dataSource;

    public MySqlDatabase(Path spoolFile, int queueCapacity, int batchSize, long flushIntervalMillis, Logger logger,
                         String jdbcUrl, String username, String password, int maximumPoolSize, int minimumIdle,
                         long connectionTimeoutMillis, long leakDetectionMillis) {
        super(spoolFile, queueCapacity, batchSize, flushIntervalMillis, logger);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.maximumPoolSize = Math.max(1, maximumPoolSize);
        this.minimumIdle = Math.max(0, Math.min(minimumIdle, this.maximumPoolSize));
        this.connectionTimeoutMillis = Math.max(250L, connectionTimeoutMillis);
        this.leakDetectionMillis = Math.max(0L, leakDetectionMillis);
    }

    @Override
    public void open() throws SQLException, IOException {
        var parent = file.toAbsolutePath().getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(minimumIdle);
        config.setConnectionTimeout(connectionTimeoutMillis);
        if (leakDetectionMillis > 0) config.setLeakDetectionThreshold(leakDetectionMillis);
        config.setPoolName("PixelProtect-MySQL");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("useUnicode", "true");
        config.addDataSourceProperty("characterEncoding", "utf8mb4");
        dataSource = new HikariDataSource(config);
        connection = dataSource.getConnection();
        connection.setAutoCommit(true);
        migrateMySql(connection);
        running = true;
        replayOverflow();
        executor.scheduleAtFixedRate(this::flushQueue, flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
        startOverflowWriter();
    }

    private static void migrateMySql(java.sql.Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS pixelprotect_meta (meta_key VARCHAR(128) PRIMARY KEY, meta_value TEXT NOT NULL) ENGINE=InnoDB");
            int version = 0;
            try (var r = s.executeQuery("SELECT meta_value FROM pixelprotect_meta WHERE meta_key='schema_version'")) {
                if (r.next()) version = Integer.parseInt(r.getString(1));
            }
            if (version < 1) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS audit (id BIGINT AUTO_INCREMENT PRIMARY KEY,time BIGINT NOT NULL,world VARCHAR(36) NOT NULL,x INT NOT NULL,y INT NOT NULL,z INT NOT NULL,actor_uuid VARCHAR(36),actor_name VARCHAR(255) NOT NULL,action VARCHAR(64) NOT NULL,before_data LONGTEXT NOT NULL,after_data LONGTEXT NOT NULL,before_inventory LONGBLOB,after_inventory LONGBLOB) ENGINE=InnoDB");
                s.executeUpdate("CREATE INDEX idx_audit_location_time ON audit(world,x,z,time DESC)");
                s.executeUpdate("CREATE INDEX idx_audit_actor_time ON audit(actor_uuid,time DESC)");
                s.executeUpdate("CREATE INDEX idx_audit_action_time ON audit(action,time DESC)");
                s.executeUpdate("CREATE INDEX idx_audit_time ON audit(time)"); version = 1;
            }
            if (version < 2) { s.executeUpdate("CREATE INDEX idx_audit_world_y_time ON audit(world,y,time DESC)"); s.executeUpdate("CREATE INDEX idx_audit_world_action_time ON audit(world,action,time DESC)"); version = 2; }
            if (version < 3) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_jobs (id VARCHAR(36) PRIMARY KEY,status VARCHAR(32) NOT NULL,total INT NOT NULL,processed INT NOT NULL DEFAULT 0,applied INT NOT NULL DEFAULT 0,skipped INT NOT NULL DEFAULT 0,error TEXT,created_at BIGINT NOT NULL,finished_at BIGINT) ENGINE=InnoDB");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_job_entries (job_id VARCHAR(36) NOT NULL,audit_id BIGINT NOT NULL,applied TINYINT NOT NULL DEFAULT 0,PRIMARY KEY(job_id,audit_id),FOREIGN KEY(job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE) ENGINE=InnoDB");
                s.executeUpdate("CREATE INDEX idx_rollback_job_entries_applied ON rollback_job_entries(job_id,applied)"); version = 3;
            }
            if (version < 4) { s.executeUpdate("CREATE TABLE IF NOT EXISTS rollback_restores (id VARCHAR(36) PRIMARY KEY,source_job_id VARCHAR(36) NOT NULL,status VARCHAR(32) NOT NULL,applied INT NOT NULL DEFAULT 0,skipped INT NOT NULL DEFAULT 0,error TEXT,created_at BIGINT NOT NULL,finished_at BIGINT,FOREIGN KEY(source_job_id) REFERENCES rollback_jobs(id) ON DELETE CASCADE) ENGINE=InnoDB"); s.executeUpdate("CREATE INDEX idx_rollback_restores_source ON rollback_restores(source_job_id,created_at DESC)"); version = 4; }
            if (version < 5) { s.executeUpdate("ALTER TABLE audit ADD COLUMN before_block_entity LONGTEXT NULL"); s.executeUpdate("ALTER TABLE audit ADD COLUMN after_block_entity LONGTEXT NULL"); version = 5; }
            if (version < 6) { s.executeUpdate("ALTER TABLE audit ADD COLUMN transaction_id VARCHAR(36) NULL"); s.executeUpdate("ALTER TABLE audit ADD COLUMN sequence BIGINT NOT NULL DEFAULT 0"); s.executeUpdate("CREATE INDEX idx_audit_transaction ON audit(transaction_id,sequence,id)"); version = 6; }
            if (version < 7) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS entity_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY,audit_id BIGINT NOT NULL,world VARCHAR(36) NOT NULL,x DOUBLE NOT NULL,y DOUBLE NOT NULL,z DOUBLE NOT NULL,actor_uuid VARCHAR(36),action VARCHAR(64) NOT NULL,before_snapshot LONGTEXT,after_snapshot LONGTEXT,spawn_reason VARCHAR(64),remove_cause VARCHAR(64),transaction_id VARCHAR(36),sequence BIGINT NOT NULL DEFAULT 0,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE) ENGINE=InnoDB");
                s.executeUpdate("CREATE INDEX idx_entity_audit_location_time ON entity_audit(world,x,y,z,audit_id)");
                s.executeUpdate("CREATE INDEX idx_entity_audit_actor ON entity_audit(actor_uuid,audit_id)");
                s.executeUpdate("CREATE INDEX idx_entity_audit_transaction ON entity_audit(transaction_id,sequence,audit_id)");
                s.executeUpdate("CREATE TABLE IF NOT EXISTS inventory_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY,audit_id BIGINT NOT NULL,world VARCHAR(36) NOT NULL,x INT NOT NULL,y INT NOT NULL,z INT NOT NULL,actor_uuid VARCHAR(36),slot_diff LONGTEXT NOT NULL,transaction_id VARCHAR(36),sequence BIGINT NOT NULL DEFAULT 0,FOREIGN KEY(audit_id) REFERENCES audit(id) ON DELETE CASCADE) ENGINE=InnoDB");
                s.executeUpdate("CREATE INDEX idx_inventory_audit_location ON inventory_audit(world,x,y,z,audit_id)");
                s.executeUpdate("CREATE INDEX idx_inventory_audit_transaction ON inventory_audit(transaction_id,sequence,audit_id)"); version = 7;
            }
            s.executeUpdate("UPDATE rollback_jobs SET status='FAILED',error='Server restarted while rollback was running.',finished_at=" + System.currentTimeMillis() + " WHERE status='RUNNING'");
            try (var u = connection.prepareStatement("INSERT INTO pixelprotect_meta(meta_key,meta_value) VALUES(?,?) ON DUPLICATE KEY UPDATE meta_value=VALUES(meta_value)")) { u.setString(1, "schema_version"); u.setString(2, Integer.toString(version)); u.executeUpdate(); }
        }
    }

    @Override
    public void close() {
        super.close();
        if (dataSource != null) dataSource.close();
    }
}
