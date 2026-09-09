package de.pixelprotect.database;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Endpoint;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;
import de.pixelprotect.service.AsyncLogQueue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class DatabaseManager implements AutoCloseable {
    private final Path dataDirectory;
    private final String mode;
    private final boolean fallbackToLocal;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Object localLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong localId = new AtomicLong();
    private HikariDataSource dataSource;
    private volatile boolean local;

    public DatabaseManager(Path dataDirectory, String mode, boolean fallbackToLocal, String host, int port,
                           String database, String username, String password, int poolSize) {
        this.dataDirectory = dataDirectory;
        this.mode = mode == null ? "local" : mode.toLowerCase(Locale.ROOT);
        this.fallbackToLocal = fallbackToLocal;
        if (!this.mode.equals("local")) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC");
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(Math.max(2, poolSize));
            config.setMinimumIdle(Math.min(2, Math.max(1, poolSize)));
            config.setConnectionTimeout(5000);
            config.setValidationTimeout(3000);
            config.setInitializationFailTimeout(5000);
            config.setPoolName("PixelProtect-MySQL");
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            dataSource = new HikariDataSource(config);
        }
    }

    public void initialize() throws SQLException {
        try {
            if (mode.equals("local")) initializeLocal(); else initializeMySql();
        } catch (Exception exception) {
            if (!fallbackToLocal) throw exception instanceof SQLException sql ? sql : new SQLException(exception);
            if (dataSource != null) dataSource.close();
            dataSource = null;
            try { initializeLocal(); }
            catch (IOException io) { throw new SQLException("Local storage initialization failed", io); }
        }
    }

    private void initializeLocal() throws IOException {
        Files.createDirectories(dataDirectory);
        local = true;
        sequence.set(Math.max(maxSequence(dataDirectory.resolve("transfer_logs.jsonl")), maxSequence(dataDirectory.resolve("block_logs.jsonl"))));
        localId.set(Math.max(maxId(dataDirectory.resolve("transfer_logs.jsonl")), maxId(dataDirectory.resolve("block_logs.jsonl"))));
    }

    private void initializeMySql() throws SQLException {
        try (Connection c=dataSource.getConnection(); Statement s=c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS pp_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value VARCHAR(255) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS transfer_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, event_sequence BIGINT NOT NULL, transaction_id CHAR(36) NOT NULL, chain_id CHAR(36) NOT NULL, rollback_id CHAR(12) NOT NULL, actor_uuid CHAR(36) NULL, actor_name VARCHAR(32) NULL, attribution_uuid CHAR(36) NULL, attribution_name VARCHAR(32) NULL, source_json LONGTEXT NOT NULL, destination_json LONGTEXT NOT NULL, items_json LONGTEXT NOT NULL, source_before LONGTEXT NOT NULL, source_after LONGTEXT NOT NULL, destination_before LONGTEXT NOT NULL, destination_after LONGTEXT NOT NULL, source_before_hash CHAR(64) NOT NULL, source_after_hash CHAR(64) NOT NULL, destination_before_hash CHAR(64) NOT NULL, destination_after_hash CHAR(64) NOT NULL, action VARCHAR(64) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY(id), UNIQUE KEY uq_transfer_rollback(rollback_id), INDEX idx_transfer_time(created_at), INDEX idx_transfer_seq(event_sequence), INDEX idx_transfer_chain(chain_id,event_sequence)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS block_logs (id BIGINT NOT NULL AUTO_INCREMENT, created_at TIMESTAMP(6) NOT NULL, event_sequence BIGINT NOT NULL, transaction_id CHAR(36) NOT NULL, rollback_id CHAR(12) NOT NULL, player_uuid CHAR(36) NOT NULL, player_name VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, before_data LONGTEXT NOT NULL, after_data LONGTEXT NOT NULL, before_inventory LONGTEXT NULL, after_inventory LONGTEXT NULL, action VARCHAR(32) NOT NULL, block_type VARCHAR(128) NOT NULL, rollback_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE', PRIMARY KEY(id), UNIQUE KEY uq_block_rollback(rollback_id), INDEX idx_block_time(created_at), INDEX idx_block_seq(event_sequence), INDEX idx_block_pos(world,x,y,z,created_at)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS endpoint_owners (endpoint_id VARCHAR(512) NOT NULL, endpoint_type VARCHAR(32) NOT NULL, world VARCHAR(128) NULL, x INT NULL, y INT NULL, z INT NULL, entity_uuid CHAR(36) NULL, owner_uuid CHAR(36) NULL, owner_name VARCHAR(32) NULL, incarnation_id CHAR(36) NULL, active BOOLEAN NOT NULL DEFAULT TRUE, placed_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(endpoint_id), INDEX idx_owner_uuid(owner_uuid), INDEX idx_owner_pos(world,x,y,z)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            migrate(c,"transfer_logs",new String[]{"event_sequence BIGINT NOT NULL DEFAULT 0","rollback_id CHAR(12) NULL","source_json LONGTEXT NULL","destination_json LONGTEXT NULL","items_json LONGTEXT NULL","source_before LONGTEXT NULL","source_after LONGTEXT NULL","destination_before LONGTEXT NULL","destination_after LONGTEXT NULL","source_before_hash CHAR(64) NULL","source_after_hash CHAR(64) NULL","destination_before_hash CHAR(64) NULL","destination_after_hash CHAR(64) NULL"});
            migrate(c,"block_logs",new String[]{"event_sequence BIGINT NOT NULL DEFAULT 0","rollback_id CHAR(12) NULL","before_inventory LONGTEXT NULL","after_inventory LONGTEXT NULL"});
            migrate(c,"endpoint_owners",new String[]{"incarnation_id CHAR(36) NULL","active BOOLEAN NOT NULL DEFAULT TRUE"});
        }
    }

    private void migrate(Connection c,String table,String[] columns)throws SQLException{for(String column:columns){try(Statement s=c.createStatement()){s.executeUpdate("ALTER TABLE "+table+" ADD COLUMN "+column);}catch(SQLException ignored){}}}
    public long nextSequence(){return sequence.incrementAndGet();}

    public void insertTransfer(TransferLog l)throws SQLException{
        if(local){synchronized(localLock){append(dataDirectory.resolve("transfer_logs.jsonl"),storedTransfer(localId.incrementAndGet(),l));}return;}
        String sql="INSERT INTO transfer_logs(created_at,event_sequence,transaction_id,chain_id,rollback_id,actor_uuid,actor_name,attribution_uuid,attribution_name,source_json,destination_json,items_json,source_before,source_after,destination_before,destination_after,source_before_hash,source_after_hash,destination_before_hash,destination_after_hash,action) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){int i=1;p.setTimestamp(i++,Timestamp.from(l.timestamp()));p.setLong(i++,l.sequence());p.setString(i++,l.transactionId().toString());p.setString(i++,l.chainId().toString());p.setString(i++,l.rollbackId());setUuid(p,i++,l.actorUuid());p.setString(i++,l.actorName());setUuid(p,i++,l.attributionUuid());p.setString(i++,l.attributionName());p.setString(i++,gson.toJson(l.source()));p.setString(i++,gson.toJson(l.destination()));p.setString(i++,gson.toJson(l.items()));p.setString(i++,l.sourceBefore());p.setString(i++,l.sourceAfter());p.setString(i++,l.destinationBefore());p.setString(i++,l.destinationAfter());p.setString(i++,l.sourceBeforeHash());p.setString(i++,l.sourceAfterHash());p.setString(i++,l.destinationBeforeHash());p.setString(i++,l.destinationAfterHash());p.setString(i,l.action());p.executeUpdate();}
    }

    public void insertBlock(BlockLog l)throws SQLException{
        if(local){synchronized(localLock){append(dataDirectory.resolve("block_logs.jsonl"),storedBlock(localId.incrementAndGet(),l));}return;}
        String sql="INSERT INTO block_logs(created_at,event_sequence,transaction_id,rollback_id,player_uuid,player_name,world,x,y,z,before_data,after_data,before_inventory,after_inventory,action,block_type) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setTimestamp(1,Timestamp.from(l.timestamp()));p.setLong(2,l.sequence());p.setString(3,l.transactionId().toString());p.setString(4,l.rollbackId());p.setString(5,l.playerUuid().toString());p.setString(6,l.playerName());p.setString(7,l.world());p.setInt(8,l.x());p.setInt(9,l.y());p.setInt(10,l.z());p.setString(11,l.beforeData());p.setString(12,l.afterData());p.setString(13,l.beforeInventory());p.setString(14,l.afterInventory());p.setString(15,l.action());p.setString(16,l.blockType());p.executeUpdate();}
    }

    public void persistOwnership(AsyncLogQueue.OwnershipRecord r)throws SQLException{
        if(local){synchronized(localLock){append(dataDirectory.resolve("endpoint_owners.jsonl"),r);}return;}
        String sql="INSERT INTO endpoint_owners(endpoint_id,endpoint_type,world,x,y,z,entity_uuid,owner_uuid,owner_name,incarnation_id,active,placed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE endpoint_type=VALUES(endpoint_type),world=VALUES(world),x=VALUES(x),y=VALUES(y),z=VALUES(z),entity_uuid=VALUES(entity_uuid),owner_uuid=VALUES(owner_uuid),owner_name=VALUES(owner_name),incarnation_id=VALUES(incarnation_id),active=VALUES(active),placed_at=VALUES(placed_at)";
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){p.setString(1,r.endpointId());p.setString(2,r.endpoint().type().name());p.setString(3,r.endpoint().world());p.setInt(4,r.endpoint().x());p.setInt(5,r.endpoint().y());p.setInt(6,r.endpoint().z());setUuid(p,7,r.endpoint().entityId());setUuid(p,8,r.owner()==null?null:r.owner().uuid());p.setString(9,r.owner()==null?null:r.owner().name());setUuid(p,10,r.incarnationId());p.setBoolean(11,r.active());p.setTimestamp(12,Timestamp.from(r.placedAt()));p.executeUpdate();}
    }

    public Optional<Owner> findOwner(String endpointId)throws SQLException{
        if(local){synchronized(localLock){Path f=dataDirectory.resolve("endpoint_owners.jsonl");if(!Files.exists(f))return Optional.empty();AsyncLogQueue.OwnershipRecord found=null;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;try{AsyncLogQueue.OwnershipRecord row=gson.fromJson(line,AsyncLogQueue.OwnershipRecord.class);if(endpointId.equals(row.endpointId()))found=row;}catch(RuntimeException e){throw new SQLException("Malformed ownership record",e);}}}catch(IOException e){throw new SQLException(e);}return found!=null&&found.active()?Optional.ofNullable(found.owner()):Optional.empty();}}
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT owner_uuid,owner_name,active FROM endpoint_owners WHERE endpoint_id=?")){p.setString(1,endpointId);try(ResultSet r=p.executeQuery()){if(r.next()&&r.getBoolean(3)&&r.getString(1)!=null)return Optional.of(new Owner(UUID.fromString(r.getString(1)),r.getString(2)));}}return Optional.empty();
    }

    public RollbackLookup findRollbackId(String raw)throws SQLException{String id=normalize(raw);if(local)return localRollback(id);List<StoredTransfer>t=new ArrayList<>();List<StoredBlock>b=new ArrayList<>();try(Connection c=dataSource.getConnection()){try(PreparedStatement p=c.prepareStatement("SELECT * FROM transfer_logs WHERE rollback_id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){while(r.next())t.add(readTransfer(r));}}try(PreparedStatement p=c.prepareStatement("SELECT * FROM block_logs WHERE rollback_id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){while(r.next())b.add(readBlock(r));}}}return new RollbackLookup(id,t,b);}

    public List<StoredTransfer> findTransfers(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{
        if(local)return localTransfers(world,x,y,z,radius,since,limit);String sql="SELECT * FROM transfer_logs WHERE created_at>=? AND ((JSON_UNQUOTE(JSON_EXTRACT(source_json,'$.world'))=? AND ABS(CAST(JSON_EXTRACT(source_json,'$.x') AS SIGNED)-?)<=? AND ABS(CAST(JSON_EXTRACT(source_json,'$.y') AS SIGNED)-?)<=? AND ABS(CAST(JSON_EXTRACT(source_json,'$.z') AS SIGNED)-?)<=?) OR (JSON_UNQUOTE(JSON_EXTRACT(destination_json,'$.world'))=? AND ABS(CAST(JSON_EXTRACT(destination_json,'$.x') AS SIGNED)-?)<=? AND ABS(CAST(JSON_EXTRACT(destination_json,'$.y') AS SIGNED)-?)<=? AND ABS(CAST(JSON_EXTRACT(destination_json,'$.z') AS SIGNED)-?)<=?)) ORDER BY event_sequence DESC LIMIT ?";List<StoredTransfer>out=new ArrayList<>();try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){int r=(int)Math.ceil(radius),i=1;p.setTimestamp(i++,Timestamp.from(since));p.setString(i++,world);p.setInt(i++,x);p.setInt(i++,r);p.setInt(i++,y);p.setInt(i++,r);p.setInt(i++,z);p.setInt(i++,r);p.setString(i++,world);p.setInt(i++,x);p.setInt(i++,r);p.setInt(i++,y);p.setInt(i++,r);p.setInt(i++,z);p.setInt(i++,r);p.setInt(i,limit);try(ResultSet rs=p.executeQuery()){while(rs.next())out.add(readTransfer(rs));}}return out;
    }

    public List<StoredBlock> findBlocks(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{
        if(local)return localBlocks(world,x,y,z,radius,since,limit);String sql="SELECT * FROM block_logs WHERE created_at>=? AND world=? AND ABS(x-?)<=? AND ABS(y-?)<=? AND ABS(z-?)<=? ORDER BY event_sequence DESC LIMIT ?";List<StoredBlock>out=new ArrayList<>();try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(sql)){int r=(int)Math.ceil(radius);p.setTimestamp(1,Timestamp.from(since));p.setString(2,world);p.setInt(3,x);p.setInt(4,r);p.setInt(5,y);p.setInt(6,r);p.setInt(7,z);p.setInt(8,r);p.setInt(9,limit);try(ResultSet rs=p.executeQuery()){while(rs.next())out.add(readBlock(rs));}}return out;
    }

    public void markTransferRolledBack(long id,String state)throws SQLException{markState("transfer_logs",id,state);}
    public void markBlockRolledBack(long id,String state)throws SQLException{markState("block_logs",id,state);}
    private void markState(String table,long id,String state)throws SQLException{if(local){updateLocalState(dataDirectory.resolve(table.equals("transfer_logs")?"transfer_logs.jsonl":"block_logs.jsonl"),id,state);return;}try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("UPDATE "+table+" SET rollback_state=? WHERE id=? AND rollback_state='ACTIVE'")){p.setString(1,state);p.setLong(2,id);p.executeUpdate();}}

    private RollbackLookup localRollback(String id)throws SQLException{List<StoredTransfer>t=new ArrayList<>();List<StoredBlock>b=new ArrayList<>();synchronized(localLock){readLocal(dataDirectory.resolve("transfer_logs.jsonl"),o->{if(id.equals(string(o,"rollbackId")))t.add(gson.fromJson(o,StoredTransfer.class));});readLocal(dataDirectory.resolve("block_logs.jsonl"),o->{if(id.equals(string(o,"rollbackId")))b.add(gson.fromJson(o,StoredBlock.class));});}return new RollbackLookup(id,t,b);}
    private List<StoredTransfer> localTransfers(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{List<StoredTransfer>out=new ArrayList<>();synchronized(localLock){readLocal(dataDirectory.resolve("transfer_logs.jsonl"),o->{StoredTransfer t=gson.fromJson(o,StoredTransfer.class);if(t.timestamp().isBefore(since)||(!touches(t.source(),world,x,y,z,radius)&&!touches(t.destination(),world,x,y,z,radius)))return;if(out.size()<limit)out.add(t);});}out.sort((a,b)->Long.compare(b.sequence(),a.sequence()));return out;}
    private List<StoredBlock> localBlocks(String world,int x,int y,int z,double radius,Instant since,int limit)throws SQLException{List<StoredBlock>out=new ArrayList<>();synchronized(localLock){readLocal(dataDirectory.resolve("block_logs.jsonl"),o->{StoredBlock b=gson.fromJson(o,StoredBlock.class);if(!b.timestamp().isBefore(since)&&world.equals(b.world())&&touches(b.x(),b.y(),b.z(),x,y,z,radius)&&out.size()<limit)out.add(b);});}out.sort((a,b)->Long.compare(b.sequence(),a.sequence()));return out;}
    private void readLocal(Path file,java.util.function.Consumer<JsonObject>consumer)throws SQLException{if(!Files.exists(file))return;try(BufferedReader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;try{consumer.accept(JsonParser.parseString(line).getAsJsonObject());}catch(RuntimeException e){throw new SQLException("Malformed Pixel-Protect local record",e);}}}catch(IOException e){throw new SQLException(e);}}
    private void append(Path file,Object object)throws SQLException{try{Files.createDirectories(file.getParent());try(BufferedWriter w=Files.newBufferedWriter(file,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){w.write(gson.toJson(object));w.newLine();w.flush();}}catch(IOException e){throw new SQLException(e);}}
    private void updateLocalState(Path file,long id,String state)throws SQLException{if(!Files.exists(file))return;synchronized(localLock){Path tmp=file.resolveSibling(file.getFileName()+".tmp");try(BufferedReader r=Files.newBufferedReader(file,StandardCharsets.UTF_8);BufferedWriter w=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;JsonObject o=JsonParser.parseString(line).getAsJsonObject();if(o.has("id")&&o.get("id").getAsLong()==id)o.addProperty("rollbackState",state);w.write(gson.toJson(o));w.newLine();}w.flush();Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(IOException|RuntimeException e){try{Files.deleteIfExists(tmp);}catch(IOException ignored){}throw new SQLException(e);}}}
    private long maxSequence(Path f)throws IOException{return maxValue(f,"sequence");}
    private long maxId(Path f)throws IOException{return maxValue(f,"id");}
    private long maxValue(Path f,String key)throws IOException{if(!Files.exists(f))return 0;long max=0;try(BufferedReader r=Files.newBufferedReader(f,StandardCharsets.UTF_8)){String line;while((line=r.readLine())!=null){if(line.isBlank())continue;try{JsonObject o=JsonParser.parseString(line).getAsJsonObject();if(o.has(key))max=Math.max(max,o.get(key).getAsLong());}catch(RuntimeException ignored){}}}return max;}

    private StoredTransfer storedTransfer(long id,TransferLog l){return new StoredTransfer(id,l.timestamp(),l.sequence(),l.transactionId(),l.chainId(),l.rollbackId(),l.actorUuid(),l.actorName(),l.attributionUuid(),l.attributionName(),l.source(),l.destination(),l.items(),l.sourceBefore(),l.sourceAfter(),l.destinationBefore(),l.destinationAfter(),l.sourceBeforeHash(),l.sourceAfterHash(),l.destinationBeforeHash(),l.destinationAfterHash(),l.action(),"ACTIVE");}
    private StoredBlock storedBlock(long id,BlockLog l){return new StoredBlock(id,l.timestamp(),l.sequence(),l.transactionId(),l.rollbackId(),l.playerUuid(),l.playerName(),l.world(),l.x(),l.y(),l.z(),l.beforeData(),l.afterData(),l.beforeInventory(),l.afterInventory(),l.action(),l.blockType(),"ACTIVE");}
    private StoredTransfer readTransfer(ResultSet r)throws SQLException{return new StoredTransfer(r.getLong("id"),r.getTimestamp("created_at").toInstant(),r.getLong("event_sequence"),UUID.fromString(r.getString("transaction_id")),UUID.fromString(r.getString("chain_id")),r.getString("rollback_id"),uuid(r.getString("actor_uuid")),r.getString("actor_name"),uuid(r.getString("attribution_uuid")),r.getString("attribution_name"),gson.fromJson(r.getString("source_json"),Endpoint.class),gson.fromJson(r.getString("destination_json"),Endpoint.class),gson.fromJson(r.getString("items_json"),new TypeToken<List<TransferLog.ItemChange>>(){}.getType()),r.getString("source_before"),r.getString("source_after"),r.getString("destination_before"),r.getString("destination_after"),r.getString("source_before_hash"),r.getString("source_after_hash"),r.getString("destination_before_hash"),r.getString("destination_after_hash"),r.getString("action"),r.getString("rollback_state"));}
    private StoredBlock readBlock(ResultSet r)throws SQLException{return new StoredBlock(r.getLong("id"),r.getTimestamp("created_at").toInstant(),r.getLong("event_sequence"),UUID.fromString(r.getString("transaction_id")),r.getString("rollback_id"),UUID.fromString(r.getString("player_uuid")),r.getString("player_name"),r.getString("world"),r.getInt("x"),r.getInt("y"),r.getInt("z"),r.getString("before_data"),r.getString("after_data"),r.getString("before_inventory"),r.getString("after_inventory"),r.getString("action"),r.getString("block_type"),r.getString("rollback_state"));}
    private static UUID uuid(String s){return s==null?null:UUID.fromString(s);}private static String string(JsonObject o,String k){return o.has(k)&&!o.get(k).isJsonNull()?o.get(k).getAsString():null;}private static boolean touches(Endpoint e,String world,int x,int y,int z,double r){return e!=null&&world.equals(e.world())&&touches(e.x(),e.y(),e.z(),x,y,z,r);}private static boolean touches(int x,int y,int z,int cx,int cy,int cz,double r){return Math.abs(x-cx)<=r&&Math.abs(y-cy)<=r&&Math.abs(z-cz)<=r;}
    private static String normalize(String raw)throws SQLException{String id=raw==null?"":raw.trim().toUpperCase(Locale.ROOT);if(!id.startsWith("#"))id="#"+id;if(!id.matches("#[0-9A-Z]{8,11}"))throw new SQLException("Invalid rollback ID");return id;}
    private static void setUuid(PreparedStatement p,int i,UUID u)throws SQLException{if(u==null)p.setNull(i,java.sql.Types.CHAR);else p.setString(i,u.toString());}
    @Override public void close(){if(dataSource!=null){dataSource.close();dataSource=null;}}

    public record RollbackLookup(String rollbackId,List<StoredTransfer> transfers,List<StoredBlock> blocks){}
    public record StoredTransfer(long id,Instant timestamp,long sequence,UUID transactionId,UUID chainId,String rollbackId,UUID actorUuid,String actorName,UUID attributionUuid,String attributionName,Endpoint source,Endpoint destination,List<TransferLog.ItemChange> items,String sourceBefore,String sourceAfter,String destinationBefore,String destinationAfter,String sourceBeforeHash,String sourceAfterHash,String destinationBeforeHash,String destinationAfterHash,String action,String rollbackState){}
    public record StoredBlock(long id,Instant timestamp,long sequence,UUID transactionId,String rollbackId,UUID playerUuid,String playerName,String world,int x,int y,int z,String beforeData,String afterData,String beforeInventory,String afterInventory,String action,String blockType,String rollbackState){}
}
