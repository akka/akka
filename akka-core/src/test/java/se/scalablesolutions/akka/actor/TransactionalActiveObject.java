package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.annotation.transactionrequired;
import se.scalablesolutions.akka.actor.annotation.prerestart;
import se.scalablesolutions.akka.actor.annotation.postrestart;
import se.scalablesolutions.akka.actor.annotation.inittransactionalstate;
import se.scalablesolutions.akka.stm.*;

@transactionrequired
public class TransactionalActiveObject {
  private TransactionalMap<String, String> mapState;
  private TransactionalVector<String> vectorState;
  private Ref<String> refState;
  private boolean isInitialized = false;
    
  @inittransactionalstate 
  public void init() {
    if (!isInitialized) {
      mapState = new TransactionalMap();
      vectorState = new TransactionalVector();
      refState = new Ref();
      isInitialized = true;
    }
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

  public void success(String key, String msg, NestedTransactionalActiveObject nested) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.success(key, msg);
  }

  public String failure(String key, String msg, ActiveObjectFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
  }

  public String failure(String key, String msg, NestedTransactionalActiveObject nested, ActiveObjectFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.failure(key, msg, failer);
    return msg;
  }

  public void thisMethodHangs(String key, String msg, ActiveObjectFailer failer) {
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
}
