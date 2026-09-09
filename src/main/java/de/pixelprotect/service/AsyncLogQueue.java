package de.pixelprotect.service;

import de.pixelprotect.database.DatabaseManager;
import de.pixelprotect.model.BlockLog;
import de.pixelprotect.model.Owner;
import de.pixelprotect.model.TransferLog;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class AsyncLogQueue implements AutoCloseable {
    private final ArrayBlockingQueue<Object> queue;
    private final DatabaseManager database;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Consumer<Throwable> errorHandler;

    public AsyncLogQueue(DatabaseManager database, int capacity, Consumer<Throwable> errorHandler) {
        this.database=database; this.queue=new ArrayBlockingQueue<>(capacity); this.errorHandler=errorHandler;
        this.worker=Thread.ofPlatform().name("PixelProtect-LogWorker").daemon(true).start(this::run);
    }

    public void submit(TransferLog log){offer(log);}
    public void submit(BlockLog log){offer(log);}
    private void offer(Object record){
        if(!running.get()) return;
        if(!queue.offer(record)) throw new IllegalStateException("Pixel-Protect logging queue is full; refusing to silently lose forensic data.");
    }

    private void run(){
        while(running.get() || !queue.isEmpty()){
            try{
                Object record=queue.poll(500,TimeUnit.MILLISECONDS);
                if(record==null)continue;
                if(record instanceof TransferLog t) database.insertTransfer(t);
                else if(record instanceof BlockLog b) database.insertBlock(b);
            }catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            catch(Throwable t){errorHandler.accept(t);}
        }
    }

    public int pending(){return queue.size();}
    @Override public void close(){running.set(false);worker.interrupt();try{worker.join(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
