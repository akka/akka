package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.inittransactionalstate;
import se.scalablesolutions.akka.annotation.transactionrequired;
import se.scalablesolutions.akka.state.*;

@transactionrequired
public class PersistentStateful {
  private PersistentMap mapState;
  private PersistentVector vectorState;
  private PersistentRef refState;

  @inittransactionalstate
  public void init() {
    mapState = CassandraStorage.newMap();
    vectorState = CassandraStorage.newVector();
    refState = CassandraStorage.newRef();
  }
 
  public String getMapState(String key) {
    byte[] bytes = (byte[]) mapState.get(key.getBytes()).get();
    return new String(bytes, 0, bytes.length);
  }
  
  public String getVectorState(int index) {
    byte[] bytes = (byte[]) vectorState.get(index);
    return new String(bytes, 0, bytes.length);
  }

  public int getVectorLength() {
    return vectorState.length();
  }

  public String getRefState() {
    if (refState.isDefined()) {
      byte[] bytes = (byte[]) refState.get().get();
      return new String(bytes, 0, bytes.length);
    } else throw new IllegalStateException("No such element");
  }
  
  public void setMapState(String key, String msg) {
    mapState.put(key.getBytes(), msg.getBytes());
  }
  
  public void setVectorState(String msg) {
    vectorState.add(msg.getBytes());
  }

  public void setRefState(String msg) {
    refState.swap(msg.getBytes());
  }

  public void success(String key, String msg) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
  }

  public String failure(String key, String msg, PersistentFailer failer) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
    failer.fail();
    return msg;
  }

  public String success(String key, String msg, PersistentStatefulNested nested) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
    nested.success(key, msg);
    return msg;
  }

  public String failure(String key, String msg, PersistentStatefulNested nested, PersistentFailer failer) {
    mapState.put(key.getBytes(), msg.getBytes());
    vectorState.add(msg.getBytes());
    refState.swap(msg.getBytes());
    nested.failure(key, msg, failer);
    return msg;
  }
}

