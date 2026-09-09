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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public final class DatabaseManager implements AutoCloseable {
    private final Path dir;
    private final String mode;
    private final boolean fallback;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final int poolSize;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object localLock = new Object();
    private HikariDataSource dataSource;
    private boolean local;
    private long transferId;
    private long blockId;

    public DatabaseManager(Path dir, String mode, boolean fallback, String host, int port, String database,
                           String username, String password, int poolSize) {
        this.dir = dir;
        this.mode = mode == null ? "local" : mode.toLowerCase(Locale.ROOT);
        this.fallback = fallback;
        this.host = host; this.port = port; this.database = database; this.username = username; this.password = password;
        this.poolSize = Math.max(2, poolSize);
    }

    public void initialize() throws SQLException {
        try {
            if (mode.equals("local")) { initializeLocal(); return; }
            initializeSql();
        } catch (Exception e) {
            if (!fallback) throw e instanceof SQLException s ? s : new SQLException(e);
            closePool();
            try { initializeLocal(); } catch (IOException io) { throw new SQLException("Local storage initialization failed", io); }
        }
    }

    private void initializeLocal() throws IOException {
        Files.createDirectories(dir);
        local = true;
        transferId = maxId("transfer_logs.jsonl");
        blockId = maxId("block_logs.jsonl");
    }

    private long maxId(String name) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) return 0;
        long max = 0;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null;) {
                if (line.isBlank()) continue;
                try { max = Math.max(max, JsonParser.parseString(line).getAsJsonObject().get("id").getAsLong()); }
                catch (RuntimeException ignored) { }
            }
        }
        return max;
    }

    private void initializeSql() throws SQLException {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
        cfg.setUsername(username); cfg.setPassword(password); cfg.setMaximumPoolSize(poolSize); cfg.setMinimumIdle(Math.min(2, poolSize));
        cfg.setConnectionTimeout(5000); cfg.setValidationTimeout(3000); cfg.setPoolName("PixelProtect-MySQL");
        cfg.addDataSourceProperty("cachePrepStmts", "true"); cfg.addDataSourceProperty("prepStmtCacheSize", "250"); cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        dataSource = new HikariDataSource(cfg);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS transfer_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, transaction_id CHAR(36) NOT NULL, actor_uuid CHAR(36) NULL, actor_name VARCHAR(32) NULL, attribution_uuid CHAR(36) NULL, attribution_name VARCHAR(32) NULL, source_type VARCHAR(32) NOT NULL, source_entity_uuid CHAR(36) NULL, source_player_uuid CHAR(36) NULL, source_world VARCHAR(128) NULL, source_x INT NULL, source_y INT NULL, source_z INT NULL, source_label VARCHAR(128) NULL, destination_type VARCHAR(32) NOT NULL, destination_entity_uuid CHAR(36) NULL, destination_player_uuid CHAR(36) NULL, destination_world VARCHAR(128) NULL, destination_x INT NULL, destination_y INT NULL, destination_z INT NULL, destination_label VARCHAR(128) NULL, item_key VARCHAR(768) NOT NULL, item_data LONGTEXT NOT NULL, amount INT NOT NULL, action VARCHAR(64) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY(id), INDEX idx_transfer_time(created_at), INDEX idx_transfer_tx(transaction_id), INDEX idx_transfer_pos(source_world,source_x,source_y,source_z,destination_world,destination_x,destination_y,destination_z)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS block_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, transaction_id CHAR(36) NOT NULL, player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, before_data LONGTEXT NOT NULL, after_data LONGTEXT NOT NULL, action VARCHAR(32) NOT NULL, block_type VARCHAR(128) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY(id), INDEX idx_block_time(created_at), INDEX idx_block_tx(transaction_id), INDEX idx_block_pos(world,x,y,z)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endpoint_owners (endpoint_id VARCHAR(512) NOT NULL, endpoint_type VARCHAR(32) NOT NULL, world VARCHAR(128) NULL, x INT NULL, y INT NULL, z INT NULL, entity_uuid CHAR(36) NULL, owner_uuid CHAR(36) NOT NULL, owner_name VARCHAR(32) NOT NULL, placed_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(endpoint_id), INDEX idx_owner_pos(world,x,y,z)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        local = false;
    }

    public void insertTransfer(TransferLog log) throws SQLException {
        if (local) { synchronized (localLock) { append("transfer_logs.jsonl", transferId++, log); } return; }
        String sql = "INSERT INTO transfer_logs(created_at,transaction_id,actor_uuid,actor_name,attribution_uuid,attribution_name,source_type,source_entity_uuid,source_player_uuid,source_world,source_x,source_y,source_z,source_label,destination_type,destination_entity_uuid,destination_player_uuid,destination_world,destination_x,destination_y,destination_z,destination_label,item_key,item_data,amount,action) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c=dataSource.getConnection(); PreparedStatement p=c.prepareStatement(sql)) {
            int i=1; p.setTimestamp(i++, Timestamp.from(log.timestamp())); p.setString(i++, log.transactionId().toString()); setUuid(p,i++,log.actorUuid()); p.setString(i++,log.actorName()); setUuid(p,i++,log.attributionUuid()); p.setString(i++,log.attributionName()); i=setEndpoint(p,i,log.source()); i=setEndpoint(p,i,log.destination()); p.setString(i++,log.itemKey()); p.setString(i++,log.itemData()); p.setInt(i++,log.amount()); p.setString(i,log.action()); p.executeUpdate();
        }
    }

    public void insertBlock(BlockLog log) throws SQLException {
        if (local) { synchronized(localLock) { append("block_logs.jsonl", blockId++, log); } return; }
        String sql="INSERT INTO block_logs(created_at,transaction_id,player_uuid,player_name,world,x,y,z,before_data,after_data,action,block_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(log.timestamp()));p.setString(2,log.transactionId().toString());p.setString(3,log.playerUuid().toString());p.setString(4,log.playerName());p.setString(5,log.world());p.setInt(6,log.x());p.setInt(7,log.y());p.setInt(8,log.z());p.setString(9,log.beforeData());p.setString(10,log.afterData());p.setString(11,log.action());p.setString(12,log.blockType());p.executeUpdate();}
    }

    public void upsertOwner(String endpointId, Endpoint endpoint, Owner owner, Instant placedAt) throws SQLException {
        if (local) { synchronized(localLock) { JsonObject o=new JsonObject();o.addProperty("endpointId",endpointId);o.add("endpoint",gson.toJsonTree(endpoint));o.add("owner",gson.toJsonTree(owner));o.addProperty("placedAt",placedAt.toEpochMilli());appendRaw("endpoint_owners.jsonl",o); } return; }
        String sql="INSERT INTO endpoint_owners(endpoint_id,endpoint_type,world,x,y,z,entity_uuid,owner_uuid,owner_name,placed_at) VALUES(?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_uuid=VALUES(owner_uuid),owner_name=VALUES(owner_name),placed_at=VALUES(placed_at)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,endpointId);p.setString(2,endpoint.type().name());p.setString(3,endpoint.world());p.setInt(4,endpoint.x());p.setInt(5,endpoint.y());p.setInt(6,endpoint.z());setUuid(p,7,endpoint.entityId());p.setString(8,owner.uuid().toString());p.setString(9,owner.name());p.setTimestamp(10,Timestamp.from(placedAt));p.executeUpdate();}
    }

    public Optional<Owner> findOwner(String endpointId) throws SQLException {
        if(local){synchronized(localLock){Path f=dir.resolve("endpoint_owners.jsonl");if(!Files.exists(f))return Optional.empty();Owner found=null;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){for(String line;(line=r.readLine())!=null;){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();if(endpointId.equals(o.get("endpointId").getAsString()))found=gson.fromJson(o.get("owner"),Owner.class);}catch(RuntimeException ignored){}}}catch(IOException e){throw new SQLException(e);}return Optional.ofNullable(found);}}
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT owner_uuid,owner_name FROM endpoint_owners WHERE endpoint_id=?")){p.setString(1,endpointId);try(ResultSet r=p.executeQuery()){if(r.next())return Optional.of(new Owner(UUID.fromString(r.getString(1)),r.getString(2)));}}return Optional.empty();
    }

    public RollbackLookup findRollback(String raw) throws SQLException {
        String id=normalizeId(raw); String prefix=id.substring(1).toLowerCase(Locale.ROOT); Set<UUID> tx=new LinkedHashSet<>(); List<StoredTransfer> transfers=new ArrayList<>(); List<StoredBlock> blocks=new ArrayList<>();
        if(local){synchronized(localLock){readLocalTransfers(prefix,transfers,tx);readLocalBlocks(prefix,blocks,tx);}}
        else {String pattern=prefix+"%";try(Connection c=dataSource.getConnection()){try(PreparedStatement p=c.prepareStatement("SELECT * FROM transfer_logs WHERE LOWER(REPLACE(transaction_id,'-','')) LIKE ? ORDER BY id ASC")){p.setString(1,pattern);try(ResultSet r=p.executeQuery()){while(r.next()){StoredTransfer s=readTransfer(r);transfers.add(s);tx.add(s.log().transactionId());}}}try(PreparedStatement p=c.prepareStatement("SELECT * FROM block_logs WHERE LOWER(REPLACE(transaction_id,'-','')) LIKE ? ORDER BY id ASC")){p.setString(1,pattern);try(ResultSet r=p.executeQuery()){while(r.next()){StoredBlock s=readBlock(r);blocks.add(s);tx.add(s.log().transactionId());}}}}}
        if(tx.isEmpty()) return new RollbackLookup(id, null, List.of(), List.of());
        if(tx.size()>1) throw new SQLException("Rollback-ID " + id + " ist nicht eindeutig. Verwende einen längeren Präfix.");
        return new RollbackLookup(id, tx.iterator().next(), transfers, blocks);
    }

    private void readLocalTransfers(String prefix,List<StoredTransfer> out,Set<UUID> tx)throws SQLException{readLocal("transfer_logs.jsonl",o->{String t=o.get("transactionId").getAsString().replace("-","").toLowerCase(Locale.ROOT);if(t.startsWith(prefix)){StoredTransfer s=new StoredTransfer(o.get("id").getAsLong(),fromTransfer(o));out.add(s);tx.add(s.log().transactionId());}});}
    private void readLocalBlocks(String prefix,List<StoredBlock> out,Set<UUID> tx)throws SQLException{readLocal("block_logs.jsonl",o->{String t=o.get("transactionId").getAsString().replace("-","").toLowerCase(Locale.ROOT);if(t.startsWith(prefix)){StoredBlock s=new StoredBlock(o.get("id").getAsLong(),fromBlock(o));out.add(s);tx.add(s.log().transactionId());}});}
    private void readLocal(String name, java.util.function.Consumer<JsonObject> consumer)throws SQLException{Path f=dir.resolve(name);if(!Files.exists(f))return;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){for(String line;(line=r.readLine())!=null;){if(line.isBlank())continue;try{consumer.accept(JsonParser.parseString(line).getAsJsonObject());}catch(RuntimeException ignored){}}}catch(IOException e){throw new SQLException(e);}}

    public void markRolledBack(UUID tx) throws SQLException {
        if(local){synchronized(localLock){rewriteState("transfer_logs.jsonl",tx);rewriteState("block_logs.jsonl",tx);}return;}
        try(Connection c=dataSource.getConnection()){try(PreparedStatement p=c.prepareStatement("UPDATE transfer_logs SET rollback_state='ROLLED_BACK' WHERE transaction_id=?")){p.setString(1,tx.toString());p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("UPDATE block_logs SET rollback_state='ROLLED_BACK' WHERE transaction_id=?")){p.setString(1,tx.toString());p.executeUpdate();}}
    }

    private void rewriteState(String name,UUID tx)throws SQLException{Path f=dir.resolve(name);if(!Files.exists(f))return;Path tmp=dir.resolve(name+".tmp");try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8);BufferedWriter w=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){for(String line;(line=r.readLine())!=null;){try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();if(tx.toString().equals(o.get("transactionId").getAsString()))o.addProperty("rollbackState","ROLLED_BACK");w.write(o.toString());w.newLine();}catch(RuntimeException e){w.write(line);w.newLine();}}}catch(IOException e){throw new SQLException(e);}try{Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException e){throw new SQLException(e);}}

    public List<StoredTransfer> inspectTransfers(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{
        if(local){List<StoredTransfer> out=new ArrayList<>();readLocal("transfer_logs.jsonl",o->{StoredTransfer s=new StoredTransfer(o.get("id").getAsLong(),fromTransfer(o));if(matches(s.log().source(),world,x,y,z,radius)||matches(s.log().destination(),world,x,y,z,radius))out.add(s);});return out.stream().sorted(Comparator.comparing((StoredTransfer s)->s.log().timestamp()).reversed()).limit(limit).toList();}
        int minX=(int)Math.floor(x-radius),maxX=(int)Math.ceil(x+radius),minY=(int)Math.floor(y-radius),maxY=(int)Math.ceil(y+radius),minZ=(int)Math.floor(z-radius),maxZ=(int)Math.ceil(z+radius);List<StoredTransfer> out=new ArrayList<>();String sql="SELECT * FROM transfer_logs WHERE created_at>=? AND ((source_world=? AND source_x BETWEEN ? AND ? AND source_y BETWEEN ? AND ? AND source_z BETWEEN ? AND ?) OR (destination_world=? AND destination_x BETWEEN ? AND ? AND destination_y BETWEEN ? AND ? AND destination_z BETWEEN ? AND ?) ) ORDER BY id DESC LIMIT ?";try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){int i=1;p.setTimestamp(i++,Timestamp.from(since));p.setString(i++,world);p.setInt(i++,minX);p.setInt(i++,maxX);p.setInt(i++,minY);p.setInt(i++,maxY);p.setInt(i++,minZ);p.setInt(i++,maxZ);p.setString(i++,world);p.setInt(i++,minX);p.setInt(i++,maxX);p.setInt(i++,minY);p.setInt(i++,maxY);p.setInt(i++,minZ);p.setInt(i++,maxZ);p.setInt(i,limit);try(ResultSet r=p.executeQuery()){while(r.next())out.add(readTransfer(r));}}return out;
    }

    public List<StoredBlock> inspectBlocks(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{
        if(local){List<StoredBlock> out=new ArrayList<>();readLocal("block_logs.jsonl",o->{StoredBlock s=new StoredBlock(o.get("id").getAsLong(),fromBlock(o));if(matches(s.log().world(),s.log().x(),s.log().y(),s.log().z(),world,x,y,z,radius))out.add(s);});return out.stream().sorted(Comparator.comparing((StoredBlock s)->s.log().timestamp()).reversed()).limit(limit).toList();}
        int minX=(int)Math.floor(x-radius),maxX=(int)Math.ceil(x+radius),minY=(int)Math.floor(y-radius),maxY=(int)Math.ceil(y+radius),minZ=(int)Math.floor(z-radius),maxZ=(int)Math.ceil(z+radius);List<StoredBlock> out=new ArrayList<>();String sql="SELECT * FROM block_logs WHERE created_at>=? AND world=? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? ORDER BY id DESC LIMIT ?";try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(since));p.setString(2,world);p.setInt(3,minX);p.setInt(4,maxX);p.setInt(5,minY);p.setInt(6,maxY);p.setInt(7,minZ);p.setInt(8,maxZ);p.setInt(9,limit);try(ResultSet r=p.executeQuery()){while(r.next())out.add(readBlock(r));}}return out;
    }

    private boolean matches(Endpoint e,String world,int x,int y,int z,double r){return e.world()!=null&&matches(e.world(),e.x(),e.y(),e.z(),world,x,y,z,r);}
    private boolean matches(String w,int ex,int ey,int ez,String world,int x,int y,int z,double r){return Objects.equals(w,world)&&Math.sqrt(Math.pow(ex-x,2)+Math.pow(ey-y,2)+Math.pow(ez-z,2))<=r;}

    private void append(String name,long id,Object object)throws SQLException{JsonObject o=gson.toJsonTree(object).getAsJsonObject();o.addProperty("id",id);appendRaw(name,o);}
    private void appendRaw(String name,JsonObject o)throws SQLException{try{Files.writeString(dir.resolve(name),o+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND);}catch(IOException e){throw new SQLException(e);}}
    private static void setUuid(PreparedStatement p,int index,UUID uuid)throws SQLException{if(uuid==null)p.setNull(index,Types.VARCHAR);else p.setString(index,uuid.toString());}
    private static int setEndpoint(PreparedStatement p,int i,Endpoint e)throws SQLException{p.setString(i++,e.type().name());setUuid(p,i++,e.entityId());setUuid(p,i++,e.playerId());p.setString(i++,e.world());if(e.world()==null){p.setNull(i++,Types.INTEGER);p.setNull(i++,Types.INTEGER);p.setNull(i++,Types.INTEGER);}else{p.setInt(i++,e.x());p.setInt(i++,e.y());p.setInt(i++,e.z());}p.setString(i++,e.label());return i;}

    private StoredTransfer readTransfer(ResultSet r)throws SQLException{Endpoint s=endpoint(r,7,8,9,10,11,12,13);Endpoint d=endpoint(r,15,16,17,18,19,20,21);TransferLog l=new TransferLog(UUID.fromString(r.getString("transaction_id")),r.getTimestamp("created_at").toInstant(),uuid(r.getString("actor_uuid")),r.getString("actor_name"),uuid(r.getString("attribution_uuid")),r.getString("attribution_name"),s,d,r.getString("item_key"),r.getString("item_data"),r.getInt("amount"),r.getString("action"));return new StoredTransfer(r.getLong("id"),l);}
    private StoredBlock readBlock(ResultSet r)throws SQLException{BlockLog l=new BlockLog(UUID.fromString(r.getString("transaction_id")),r.getTimestamp("created_at").toInstant(),UUID.fromString(r.getString("player_uuid")),r.getString("player_name"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("after_data"),r.getString("action"),r.getString("block_type"));return new StoredBlock(r.getLong("id"),l);}
    private Endpoint endpoint(ResultSet r,int type,int entity,int player,int world,int x,int y,int z)throws SQLException{return new Endpoint(EndpointType.valueOf(r.getString(type)),uuid(r.getString(entity)),uuid(r.getString(player)),r.getString(world),r.getInt(x),r.getInt(y),r.getInt(z),r.getString(world+4));}
    private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}

    private TransferLog fromTransfer(JsonObject o){return gson.fromJson(o,TransferLog.class);}
    private BlockLog fromBlock(JsonObject o){return gson.fromJson(o,BlockLog.class);}
    private static String normalizeId(String raw)throws SQLException{if(raw==null)throw new SQLException("Rollback-ID fehlt.");String s=raw.trim().toUpperCase(Locale.ROOT);if(!s.startsWith("#"))s="#"+s;if(s.length()<3||s.length()>37||!s.substring(1).matches("[0-9A-F]+"))throw new SQLException("Ungültige Rollback-ID: "+raw);return s;}

    public record StoredTransfer(long id,TransferLog log){}
    public record StoredBlock(long id,BlockLog log){}
    public record RollbackLookup(String id,UUID transactionId,List<StoredTransfer> transfers,List<StoredBlock> blocks){public boolean found(){return transactionId!=null;}}

    @Override public void close(){closePool();}
    private void closePool(){if(dataSource!=null){dataSource.close();dataSource=null;}}
}
