package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.transactional;
import se.scalablesolutions.akka.kernel.state.*;

public class InMemStatefulNested {
  private TransactionalState factory = new TransactionalState();
  private TransactionalMap<String, String> mapState = factory.newInMemoryMap();
  private TransactionalVector<String> vectorState = factory.newInMemoryVector();
  private TransactionalRef<String> refState = factory.newInMemoryRef();

  @transactional
  public String getMapState(String key) {
    return (String)mapState.get(key).get();
  }

  @transactional
  public String getVectorState() {
    return (String)vectorState.last();
  }

  @transactional
  public String getRefState() {
    return (String)refState.get().get();
  }

  @transactional
  public void setMapState(String key, String msg) {
    mapState.put(key, msg);
  }

  @transactional
  public void setVectorState(String msg) {
    vectorState.add(msg);
  }

  @transactional
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
  public String failure(String key, String msg, InMemFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
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