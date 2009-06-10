package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.kernel.*;
import se.scalablesolutions.akka.annotation.transactional;
import se.scalablesolutions.akka.annotation.state;

public class PersistentStateful {
  private TransactionalMap mapState = new CassandraPersistentTransactionalMap(this);
  private TransactionalVector vectorState = new CassandraPersistentTransactionalVector(this);
  //private TransactionalRef refState = new CassandraPersistentTransactionalRef(this);

  public String getMapState(String key) {
    return (String) mapState.get(key).get();
  }

  public String getVectorState() {
    return (String) vectorState.first();
  }

//  public String getRefState() {
//    return (String) refState.get().get();
//  }

  public void setMapState(String key, String msg) {
    mapState.put(key, msg);
  }

  public void setVectorState(String msg) {
    vectorState.add(msg);
  }

//  public void setRefState(String msg) {
//    refState.swap(msg);
//  }

  @transactional
  public void success(String key, String msg) {
    mapState.put(key, msg);
    vectorState.add(msg);
//    refState.swap(msg);
  }

  @transactional
  public void failure(String key, String msg, PersistentFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
//    refState.swap(msg);
    failer.fail();
  }

  @transactional
  public void thisMethodHangs(String key, String msg, PersistentFailer failer) {
    setMapState(key, msg);
  }
}

