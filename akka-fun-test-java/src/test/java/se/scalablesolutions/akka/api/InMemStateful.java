package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.transactionrequired;
import se.scalablesolutions.akka.annotation.prerestart;
import se.scalablesolutions.akka.annotation.postrestart;
import se.scalablesolutions.akka.annotation.inittransactionalstate;
import se.scalablesolutions.akka.state.*;

@transactionrequired
public class InMemStateful {
  private TransactionalMap<String, String> mapState;
  private TransactionalVector<String> vectorState;
  private TransactionalRef<String> refState;

  @inittransactionalstate
  public void init() {
    mapState = TransactionalState.newMap();
    vectorState = TransactionalState.newVector();
    refState = TransactionalState.newRef();
  }
  
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

  public void success(String key, String msg) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
  }

  public void success(String key, String msg, InMemStatefulNested nested) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.success(key, msg); 
  }

  public String failure(String key, String msg, InMemFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
  }

  public String failure(String key, String msg, InMemStatefulNested nested, InMemFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.failure(key, msg, failer);
    return msg;
  }

  public void thisMethodHangs(String key, String msg, InMemFailer failer) {
    setMapState(key, msg);
  }

  @prerestart
  public void preRestart() {
    System.out.println("################ PRE RESTART");
  }

  @postrestart
  public void postRestart() {
    System.out.println("################ POST RESTART");
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
