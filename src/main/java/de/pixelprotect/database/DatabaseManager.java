package de.pixelprotect.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.EndpointType;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class DatabaseManager implements AutoCloseable {
    private final Path dataDirectory;
    private final String mode;
    private final boolean fallbackToLocal;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private HikariDataSource dataSource;
    private volatile boolean local;
    private final Object localLock = new Object();
    private long localTransferId;
    private long localBlockId;

    public DatabaseManager(Path dataDirectory, String mode, boolean fallbackToLocal, String host, int port, String database, String username, String password, int poolSize) {
        this.dataDirectory = dataDirectory;
        this.mode = mode == null ? "local" : mode.toLowerCase(Locale.ROOT);
        this.fallbackToLocal = fallbackToLocal;
        if (!this.mode.equals("local")) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(poolSize);
            config.setMinimumIdle(Math.min(2, poolSize));
            config.setConnectionTimeout(5000);
            config.setValidationTimeout(3000);
            config.setPoolName("PixelProtect-MySQL");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            this.dataSource = new HikariDataSource(config);
        }
    }

    public void initialize() throws SQLException {
        try {
            if (mode.equals("local")) {
                initializeLocal();
                return;
            }
            initializeMySql();
        } catch (Exception failure) {
            if (!fallbackToLocal) throw failure instanceof SQLException sql ? sql : new SQLException(failure);
            if (dataSource != null) dataSource.close();
            dataSource = null;
            try {
                initializeLocal();
            } catch (IOException localFailure) {
                throw new SQLException("Lokale Pixel-Protect-Speicherinitialisierung fehlgeschlagen.", localFailure);
            }
        }
    }

    private void initializeMySql() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS transfer_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, transaction_id CHAR(36) NOT NULL, chain_id CHAR(36) NOT NULL, actor_uuid CHAR(36) NULL, actor_name VARCHAR(32) NULL, attribution_uuid CHAR(36) NULL, attribution_name VARCHAR(32) NULL, source_type VARCHAR(32) NOT NULL, source_entity_uuid CHAR(36) NULL, source_player_uuid CHAR(36) NULL, source_world VARCHAR(128) NULL, source_x INT NULL, source_y INT NULL, source_z INT NULL, source_label VARCHAR(128) NULL, destination_type VARCHAR(32) NOT NULL, destination_entity_uuid CHAR(36) NULL, destination_player_uuid CHAR(36) NULL, destination_world VARCHAR(128) NULL, destination_x INT NULL, destination_y INT NULL, destination_z INT NULL, destination_label VARCHAR(128) NULL, item_key VARCHAR(768) NOT NULL, item_data LONGTEXT NOT NULL, amount INT NOT NULL, action VARCHAR(64) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY (id), INDEX idx_transfer_time (created_at), INDEX idx_transfer_actor (actor_uuid, created_at), INDEX idx_transfer_chain (chain_id, id), INDEX idx_transfer_source (source_world, source_x, source_y, source_z, created_at), INDEX idx_transfer_dest (destination_world, destination_x, destination_y, destination_z, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS block_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, transaction_id CHAR(36) NOT NULL, player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, before_data LONGTEXT NOT NULL, after_data LONGTEXT NOT NULL, action VARCHAR(32) NOT NULL, block_type VARCHAR(128) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY (id), INDEX idx_block_time (created_at), INDEX idx_block_actor (player_uuid, created_at), INDEX idx_block_pos (world, x, y, z, created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endpoint_owners (endpoint_id VARCHAR(512) NOT NULL, endpoint_type VARCHAR(32) NOT NULL, world VARCHAR(128) NULL, x INT NULL, y INT NULL, z INT NULL, entity_uuid CHAR(36) NULL, owner_uuid CHAR(36) NOT NULL, owner_name VARCHAR(32) NOT NULL, placed_at TIMESTAMP(6) NOT NULL, PRIMARY KEY (endpoint_id), INDEX idx_owner_uuid (owner_uuid), INDEX idx_owner_pos (world, x, y, z)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    private void initializeLocal() throws IOException {
        Files.createDirectories(dataDirectory);
        local = true;
        localTransferId = maxId(dataDirectory.resolve("transfer_logs.jsonl"));
        localBlockId = maxId(dataDirectory.resolve("block_logs.jsonl"));
    }

    private long maxId(Path file) throws IOException {
        if (!Files.exists(file)) return 0;
        long max = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try { max = Math.max(max, JsonParser.parseString(line).getAsJsonObject().get("id").getAsLong()); } catch (RuntimeException ignored) { }
            }
        }
        return max;
    }

    public void insertTransfer(TransferLog log) throws SQLException {
        if (local) { synchronized (localLock) { appendTransfer(++localTransferId, log); } return; }
        String sql = "INSERT INTO transfer_logs (created_at,transaction_id,chain_id,actor_uuid,actor_name,attribution_uuid,attribution_name,source_type,source_entity_uuid,source_player_uuid,source_world,source_x,source_y,source_z,source_label,destination_type,destination_entity_uuid,destination_player_uuid,destination_world,destination_x,destination_y,destination_z,destination_label,item_key,item_data,amount,action) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement(sql)) {
            int i=1; p.setTimestamp(i++,Timestamp.from(log.timestamp())); p.setString(i++,log.transactionId().toString()); p.setString(i++,log.chainId().toString()); setUuid(p,i++,log.actorUuid()); p.setString(i++,log.actorName()); setUuid(p,i++,log.attributionUuid()); p.setString(i++,log.attributionName()); i=setEndpoint(p,i,log.source()); i=setEndpoint(p,i,log.destination()); p.setString(i++,log.itemKey()); p.setString(i++,log.itemData()); p.setInt(i++,log.amount()); p.setString(i,log.action()); p.executeUpdate();
        }
    }

    public void insertBlock(BlockLog log) throws SQLException {
        if (local) { synchronized (localLock) { appendBlock(++localBlockId, log); } return; }
        String sql="INSERT INTO block_logs (created_at,transaction_id,player_uuid,player_name,world,x,y,z,before_data,after_data,action,block_type) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(log.timestamp()));p.setString(2,log.transactionId().toString());p.setString(3,log.playerUuid().toString());p.setString(4,log.playerName());p.setString(5,log.world());p.setInt(6,log.x());p.setInt(7,log.y());p.setInt(8,log.z());p.setString(9,log.beforeData());p.setString(10,log.afterData());p.setString(11,log.action());p.setString(12,log.blockType());p.executeUpdate();}
    }

    public void upsertOwner(String endpointId, Endpoint endpoint, Owner owner, Instant placedAt) throws SQLException {
        if (local) { synchronized(localLock) { JsonObject o=new JsonObject(); o.addProperty("endpointId",endpointId); o.add("endpoint",gson.toJsonTree(endpoint)); o.add("owner",gson.toJsonTree(owner)); o.addProperty("placedAt",placedAt.toEpochMilli()); append(dataDirectory.resolve("endpoint_owners.jsonl"),o); } return; }
        String sql="INSERT INTO endpoint_owners (endpoint_id,endpoint_type,world,x,y,z,entity_uuid,owner_uuid,owner_name,placed_at) VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_uuid=VALUES(owner_uuid),owner_name=VALUES(owner_name),placed_at=VALUES(placed_at)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,endpointId);p.setString(2,endpoint.type().name());p.setString(3,endpoint.world());p.setInt(4,endpoint.x());p.setInt(5,endpoint.y());p.setInt(6,endpoint.z());setUuid(p,7,endpoint.entityId());p.setString(8,owner.uuid().toString());p.setString(9,owner.name());p.setTimestamp(10,Timestamp.from(placedAt));p.executeUpdate();}
    }

    public Optional<Owner> findOwner(String endpointId) throws SQLException {
        if (local) {
            synchronized (localLock) {
                Path f = dataDirectory.resolve("endpoint_owners.jsonl");
                if (!Files.exists(f)) return Optional.empty();
                Owner found = null;
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isBlank()) continue;
                        JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                        if (endpointId.equals(o.get("endpointId").getAsString())) found = gson.fromJson(o.get("owner"), Owner.class);
                    }
                } catch (IOException | RuntimeException e) { throw new SQLException(e); }
                return Optional.ofNullable(found);
            }
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement p = c.prepareStatement("SELECT owner_uuid,owner_name FROM endpoint_owners WHERE endpoint_id=?")) {
            p.setString(1, endpointId);
            try (ResultSet r = p.executeQuery()) { if (r.next()) return Optional.of(new Owner(UUID.fromString(r.getString(1)), r.getString(2))); }
        }
        return Optional.empty();
    }

    public List<StoredTransfer> findTransfers(String world,int cx,int cy,int cz,double radius,Instant since,int limit)throws SQLException{
        if(local)return localTransfers(world,cx,cy,cz,radius,since,limit);
        double r=radius;String sql="SELECT * FROM transfer_logs WHERE created_at>=? AND ((source_world=? AND source_x BETWEEN ? AND ? AND source_y BETWEEN ? AND ? AND source_z BETWEEN ? AND ?) OR (destination_world=? AND destination_x BETWEEN ? AND ? AND destination_y BETWEEN ? AND ? AND destination_z BETWEEN ? AND ?)) ORDER BY id DESC LIMIT ?";List<StoredTransfer> out=new ArrayList<>();try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){int minX=(int)Math.floor(cx-r),maxX=(int)Math.ceil(cx+r),minY=(int)Math.floor(cy-r),maxY=(int)Math.ceil(cy+r),minZ=(int)Math.floor(cz-r),maxZ=(int)Math.ceil(cz+r);int i=1;p.setTimestamp(i++,Timestamp.from(since));p.setString(i++,world);p.setInt(i++,minX);p.setInt(i++,maxX);p.setInt(i++,minY);p.setInt(i++,maxY);p.setInt(i++,minZ);p.setInt(i++,maxZ);p.setString(i++,world);p.setInt(i++,minX);p.setInt(i++,maxX);p.setInt(i++,minY);p.setInt(i++,maxY);p.setInt(i++,minZ);p.setInt(i++,maxZ);p.setInt(i,limit);try(ResultSet rset=p.executeQuery()){while(rset.next())out.add(readTransfer(rset));}}return out;
    }

    public List<StoredBlock> findBlocks(String world,int cx,int cy,int cz,double radius,Instant since,int limit)throws SQLException{
        if(local)return localBlocks(world,cx,cy,cz,radius,since,limit);
        String sql="SELECT * FROM block_logs WHERE created_at>=? AND world=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? ORDER BY id DESC LIMIT ?";List<StoredBlock> out=new ArrayList<>();int minX=(int)Math.floor(cx-radius),maxX=(int)Math.ceil(cx+radius),minY=(int)Math.floor(cy-radius),maxY=(int)Math.ceil(cy+radius),minZ=(int)Math.floor(cz-radius),maxZ=(int)Math.ceil(cz+radius);try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(since));p.setString(2,world);p.setInt(3,minX);p.setInt(4,maxX);p.setInt(5,minY);p.setInt(6,maxY);p.setInt(7,minZ);p.setInt(8,maxZ);p.setInt(9,limit);try(ResultSet r=p.executeQuery()){while(r.next())out.add(readBlock(r));}}return out;
    }

    public RollbackLookup findRollbackId(String rawId) throws SQLException {
        String id = normalizeRollbackId(rawId);
        if (local) return localRollbackLookup(id);
        String prefix = id.substring(1).toLowerCase(Locale.ROOT) + "%";
        List<StoredTransfer> transfers = new ArrayList<>();
        List<StoredBlock> blocks = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM transfer_logs WHERE LOWER(REPLACE(transaction_id,'-','')) LIKE ? ORDER BY id ASC")) {
                p.setString(1, prefix);
                try (ResultSet r = p.executeQuery()) { while (r.next()) transfers.add(readTransfer(r)); }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM block_logs WHERE LOWER(REPLACE(transaction_id,'-','')) LIKE ? ORDER BY id ASC")) {
                p.setString(1, prefix);
                try (ResultSet r = p.executeQuery()) { while (r.next()) blocks.add(readBlock(r)); }
            }
        }
        return new RollbackLookup(id, transfers, blocks);
    }

    private RollbackLookup localRollbackLookup(String id) throws SQLException {
        String prefix = id.substring(1).toUpperCase(Locale.ROOT);
        List<StoredTransfer> transfers = new ArrayList<>();
        List<StoredBlock> blocks = new ArrayList<>();
        synchronized (localLock) {
            Path tf = dataDirectory.resolve("transfer_logs.jsonl");
            if (Files.exists(tf)) {
                try (BufferedReader r=Files.newBufferedReader(tf,StandardCharsets.UTF_8)) {
                    String line; while((line=r.readLine())!=null){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();String tx=o.get("transactionId").getAsString().replace("-","").toUpperCase(Locale.ROOT);if(tx.startsWith(prefix))transfers.add(readLocalTransfer(o));}catch(RuntimeException ignored){}}
                } catch(IOException e){throw new SQLException(e);}
            }
            Path bf = dataDirectory.resolve("block_logs.jsonl");
            if (Files.exists(bf)) {
                try (BufferedReader r=Files.newBufferedReader(bf,StandardCharsets.UTF_8)) {
                    String line; while((line=r.readLine())!=null){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();String tx=o.get("transactionId").getAsString().replace("-","").toUpperCase(Locale.ROOT);if(tx.startsWith(prefix))blocks.add(readLocalBlock(o));}catch(RuntimeException ignored){}}
                } catch(IOException e){throw new SQLException(e);}
            }
        }
        return new RollbackLookup(id, transfers, blocks);
    }

    private static String normalizeRollbackId(String raw) throws SQLException {
        if (raw == null) throw new SQLException("Rollback-ID fehlt.");
        String id = raw.trim().toUpperCase(Locale.ROOT);
        if (!id.startsWith("#")) id = "#" + id;
        if (!id.matches("#[0-9A-F]{5}")) throw new SQLException("Ungueltige Rollback-ID. Erwartet wird z.B. #A7F31.");
        return id;
    }

    public void markTransferRolledBack(long id,String state)throws SQLException{if(local){updateState(dataDirectory.resolve("transfer_logs.jsonl"),id,state);return;}try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("UPDATE transfer_logs SET rollback_state=? WHERE id=?")){p.setString(1,state);p.setLong(2,id);p.executeUpdate();}}
    public void markBlockRolledBack(long id,String state)throws SQLException{if(local){updateState(dataDirectory.resolve("block_logs.jsonl"),id,state);return;}try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("UPDATE block_logs SET rollback_state=? WHERE id=?")){p.setString(1,state);p.setLong(2,id);p.executeUpdate();}}

    private void appendTransfer(long id,TransferLog l)throws SQLException{JsonObject o=new JsonObject();o.addProperty("id",id);o.addProperty("timestamp",l.timestamp().toEpochMilli());o.addProperty("transactionId",l.transactionId().toString());o.addProperty("chainId",l.chainId().toString());addUuid(o,"actorUuid",l.actorUuid());o.addProperty("actorName",l.actorName());addUuid(o,"attributionUuid",l.attributionUuid());o.addProperty("attributionName",l.attributionName());o.add("source",gson.toJsonTree(l.source()));o.add("destination",gson.toJsonTree(l.destination()));o.addProperty("itemKey",l.itemKey());o.addProperty("itemData",l.itemData());o.addProperty("amount",l.amount());o.addProperty("action",l.action());o.addProperty("rollbackState","ACTIVE");append(dataDirectory.resolve("transfer_logs.jsonl"),o);}
    private void appendBlock(long id,BlockLog l)throws SQLException{JsonObject o=new JsonObject();o.addProperty("id",id);o.addProperty("timestamp",l.timestamp().toEpochMilli());o.addProperty("transactionId",l.transactionId().toString());o.addProperty("playerUuid",l.playerUuid().toString());o.addProperty("playerName",l.playerName());o.addProperty("world",l.world());o.addProperty("x",l.x());o.addProperty("y",l.y());o.addProperty("z",l.z());o.addProperty("beforeData",l.beforeData());o.addProperty("afterData",l.afterData());o.addProperty("action",l.action());o.addProperty("blockType",l.blockType());o.addProperty("rollbackState","ACTIVE");append(dataDirectory.resolve("block_logs.jsonl"),o);}
    private void append(Path file,JsonObject o)throws SQLException{try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){w.write(gson.toJson(o));w.newLine();}catch(IOException e){throw new SQLException(e);}}

    private List<StoredTransfer> localTransfers(String world,int cx,int cy,int cz,double radius,Instant since,int limit)throws SQLException{synchronized(localLock){List<StoredTransfer> out=new ArrayList<>();Path f=dataDirectory.resolve("transfer_logs.jsonl");if(!Files.exists(f))return out;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();StoredTransfer t=readLocalTransfer(o);if(t.timestamp().isBefore(since)||(!near(t.source(),world,cx,cy,cz,radius)&&!near(t.destination(),world,cx,cy,cz,radius)))continue;out.add(t);}catch(RuntimeException ignored){}}}catch(IOException e){throw new SQLException(e);}out.sort(Comparator.comparing(StoredTransfer::timestamp).reversed());if(out.size()>limit)return new ArrayList<>(out.subList(0,limit));return out;}}
    private List<StoredBlock> localBlocks(String world,int cx,int cy,int cz,double radius,Instant since,int limit)throws SQLException{synchronized(localLock){List<StoredBlock> out=new ArrayList<>();Path f=dataDirectory.resolve("block_logs.jsonl");if(!Files.exists(f))return out;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();StoredBlock b=readLocalBlock(o);if(b.timestamp().isBefore(since)||!near(b.world(),b.x(),b.y(),b.z(),world,cx,cy,cz,radius))continue;out.add(b);}catch(RuntimeException ignored){}}}catch(IOException e){throw new SQLException(e);}out.sort(Comparator.comparing(StoredBlock::timestamp).reversed());if(out.size()>limit)return new ArrayList<>(out.subList(0,limit));return out;}}
    private boolean near(Endpoint e,String world,int x,int y,int z,double r){return e.world()!=null&&e.world().equals(world)&&distance(e.x(),e.y(),e.z(),x,y,z)<=r;}
    private boolean near(String w,int x,int y,int z,String world,int cx,int cy,int cz,double r){return w.equals(world)&&distance(x,y,z,cx,cy,cz)<=r;}
    private double distance(int x,int y,int z,int cx,int cy,int cz){long dx=(long)x-cx,dy=(long)y-cy,dz=(long)z-cz;return Math.sqrt(dx*dx+dy*dy+dz*dz);}
    private StoredTransfer readLocalTransfer(JsonObject o){return new StoredTransfer(o.get("id").getAsLong(),Instant.ofEpochMilli(o.get("timestamp").getAsLong()),UUID.fromString(o.get("transactionId").getAsString()),UUID.fromString(o.get("chainId").getAsString()),nullableUuid(o,"actorUuid"),nullableString(o,"actorName"),nullableUuid(o,"attributionUuid"),nullableString(o,"attributionName"),gson.fromJson(o.get("source"),Endpoint.class),gson.fromJson(o.get("destination"),Endpoint.class),o.get("itemKey").getAsString(),o.get("itemData").getAsString(),o.get("amount").getAsInt(),o.get("action").getAsString(),o.get("rollbackState").getAsString());}
    private StoredBlock readLocalBlock(JsonObject o){return new StoredBlock(o.get("id").getAsLong(),Instant.ofEpochMilli(o.get("timestamp").getAsLong()),UUID.fromString(o.get("transactionId").getAsString()),UUID.fromString(o.get("playerUuid").getAsString()),o.get("playerName").getAsString(),o.get("world").getAsString(),o.get("x").getAsInt(),o.get("y").getAsInt(),o.get("z").getAsInt(),o.get("beforeData").getAsString(),o.get("afterData").getAsString(),o.get("action").getAsString(),o.get("blockType").getAsString(),o.get("rollbackState").getAsString());}
    private UUID nullableUuid(JsonObject o,String k){return !o.has(k)||o.get(k).isJsonNull()?null:UUID.fromString(o.get(k).getAsString());}
    private String nullableString(JsonObject o,String k){return !o.has(k)||o.get(k).isJsonNull()?null:o.get(k).getAsString();}
    private void updateState(Path file,long id,String state)throws SQLException{synchronized(localLock){if(!Files.exists(file))return;try{List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);for(int i=0;i<lines.size();i++){try{JsonObject o=JsonParser.parseString(lines.get(i)).getAsJsonObject();if(o.get("id").getAsLong()==id)o.addProperty("rollbackState",state);lines.set(i,gson.toJson(o));}catch(RuntimeException ignored){}}Path tmp=file.resolveSibling(file.getFileName()+".tmp");Files.write(tmp,lines,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);Files.move(tmp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(IOException e){throw new SQLException(e);}}}

    private StoredTransfer readTransfer(ResultSet r)throws SQLException{Endpoint source=readEndpoint(r,"source");Endpoint dest=readEndpoint(r,"destination");return new StoredTransfer(r.getLong("id"),r.getTimestamp("created_at").toInstant(),UUID.fromString(r.getString("transaction_id")),UUID.fromString(r.getString("chain_id")),nullableUuid(r.getString("actor_uuid")),r.getString("actor_name"),nullableUuid(r.getString("attribution_uuid")),r.getString("attribution_name"),source,dest,r.getString("item_key"),r.getString("item_data"),r.getInt("amount"),r.getString("action"),r.getString("rollback_state"));}
    private StoredBlock readBlock(ResultSet r)throws SQLException{return new StoredBlock(r.getLong("id"),r.getTimestamp("created_at").toInstant(),UUID.fromString(r.getString("transaction_id")),UUID.fromString(r.getString("player_uuid")),r.getString("player_name"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("after_data"),r.getString("action"),r.getString("block_type"),r.getString("rollback_state"));}
    private static int setEndpoint(PreparedStatement p,int i,Endpoint e)throws SQLException{p.setString(i++,e.type().name());setUuid(p,i++,e.entityId());setUuid(p,i++,e.playerId());p.setString(i++,e.world());if(e.world()==null){p.setNull(i++,Types.INTEGER);p.setNull(i++,Types.INTEGER);p.setNull(i++,Types.INTEGER);}else{p.setInt(i++,e.x());p.setInt(i++,e.y());p.setInt(i++,e.z());}p.setString(i++,e.label());return i;}
    private static Endpoint readEndpoint(ResultSet r,String prefix)throws SQLException{EndpointType type=EndpointType.valueOf(r.getString(prefix+"_type"));UUID entity=nullableUuid(r.getString(prefix+"_entity_uuid"));UUID player=nullableUuid(r.getString(prefix+"_player_uuid"));String world=r.getString(prefix+"_world");int x=r.getInt(prefix+"_x"),y=r.getInt(prefix+"_y"),z=r.getInt(prefix+"_z");return new Endpoint(type,entity,player,world,x,y,z,r.getString(prefix+"_label"));}
    private static void setUuid(PreparedStatement p,int index,UUID uuid)throws SQLException{if(uuid==null)p.setNull(index,Types.CHAR);else p.setString(index,uuid.toString());}
    private static void addUuid(JsonObject o,String key,UUID value){if(value==null)o.add(key,com.google.gson.JsonNull.INSTANCE);else o.addProperty(key,value.toString());}
    private static UUID nullableUuid(String s){return s==null?null:UUID.fromString(s);}

    public record RollbackLookup(String rollbackId,List<StoredTransfer> transfers,List<StoredBlock> blocks) {}
    public record StoredTransfer(long id,Instant timestamp,UUID transactionId,UUID chainId,UUID actorUuid,String actorName,UUID attributionUuid,String attributionName,Endpoint source,Endpoint destination,String itemKey,String itemData,int amount,String action,String rollbackState){}
    public record StoredBlock(long id,Instant timestamp,UUID transactionId,UUID playerUuid,String playerName,String world,int x,int y,int z,String beforeData,String afterData,String action,String blockType,String rollbackState){}
    @Override public void close(){if(dataSource!=null)dataSource.close();}
}
