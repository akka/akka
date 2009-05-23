package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.CassandraPersistentTransactionalMap;
import se.scalablesolutions.akka.annotation.transactional;

public class PersistentStateful {
  private TransactionalMap state = new CassandraPersistentTransactionalMap(this);

  public String getState(String key) {
    return (String)state.get(key).get();
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  @transactional
  public void success(String key, String msg) {
    state.put(key, msg);
  }

  @transactional
  public void failure(String key, String msg, PersistentFailer failer) {
    state.put(key, msg);
    failer.fail();
  }

  @transactional
  public void clashOk(String key, String msg, PersistentClasher clasher) {
    state.put(key, msg);
    clasher.clash();
  }

  @transactional
  public void clashNotOk(String key, String msg, PersistentClasher clasher) {
    state.put(key, msg);
    clasher.clash();
    clasher.clash();
  }
}

