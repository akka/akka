/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package sample.rest.java;

import akka.actor.TypedActor;
import akka.stm.Atomic;
import akka.persistence.common.PersistentMap;
import akka.persistence.cassandra.CassandraStorage;

import java.nio.ByteBuffer;

public class PersistentSimpleServiceImpl extends TypedActor implements PersistentSimpleService {
  private String KEY = "COUNTER";

  private boolean hasStartedTicking = false;
  private final PersistentMap<byte[], byte[]> storage = CassandraStorage.newMap();

  public String count() {
    if (!hasStartedTicking) {
      new Atomic() {
        public Object atomically() {
          storage.put(KEY.getBytes(), ByteBuffer.allocate(4).putInt(0).array());
          return null;
        }
      }.execute();
      hasStartedTicking = true;
      return "Tick: 0\n";
    } else {
      int counter = new Atomic<Integer>() {
        public Integer atomically() {
          byte[] bytes = (byte[])storage.get(KEY.getBytes()).get();
          int count = ByteBuffer.wrap(bytes).getInt() + 1;
          storage.put(KEY.getBytes(), ByteBuffer.allocate(4).putInt(count).array());
          return count;
        }
      }.execute();
      return "Tick: " + counter + "\n";
    }
  }

  @Override
  public void preRestart(Throwable cause) {
    System.out.println("Prepare for restart by supervisor");
  }

  @Override
  public void postRestart(Throwable cause) {
    System.out.println("Reinitialize after restart by supervisor");
  }
}
