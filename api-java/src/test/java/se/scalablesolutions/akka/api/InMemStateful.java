package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.state;
import se.scalablesolutions.akka.annotation.transactional;
import se.scalablesolutions.akka.kernel.*;

public class InMemStateful {
  @state private TransactionalMap<String, String> mapState = new InMemoryTransactionalMap<String, String>();
  @state private TransactionalVector<String> vectorState = new InMemoryTransactionalVector<String>();
  @state private TransactionalRef<String> refState = new TransactionalRef<String>();

  public String getMapState(String key) {
    return (String)mapState.get(key).get();
  }

  public String getVectorState() {
    return (String)vectorState.last();
  }

  public String getRefState() {
    return (String)refState.get().get();
  }

  public void setMapState(String key, String msg) {
    mapState.put(key, msg);
  }

  public void setVectorState(String msg) {
    vectorState.add(msg);
  }

  public void setRefState(String msg) {
    refState.swap(msg);
  }

  @transactional
  public void success(String key, String msg) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
  }

  @transactional
  public void failure(String key, String msg, InMemFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
  }

  @transactional
  public void thisMethodHangs(String key, String msg, InMemFailer failer) {
    setMapState(key, msg);
  }

  /*
  public void clashOk(String key, String msg, InMemClasher clasher) {
    mapState.put(key, msg);
    clasher.clash();
  }

  public void clashNotOk(String key, String msg, InMemClasher clasher) {
    mapState.put(key, msg);
    clasher.clash();
    this.success("clash", "clash");
  }
  */
}