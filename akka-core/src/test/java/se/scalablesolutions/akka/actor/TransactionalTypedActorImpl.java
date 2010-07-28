package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;
import se.scalablesolutions.akka.stm.*;

public class TransactionalTypedActorImpl extends TypedTransactor implements TransactionalTypedActor {
  private TransactionalMap<String, String> mapState;
  private TransactionalVector<String> vectorState;
  private Ref<String> refState;
  private boolean isInitialized = false;
  
  @Override  
  public void initTransactionalState() {
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

  public void success(String key, String msg, NestedTransactionalTypedActor nested) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.success(key, msg);
  }

  public String failure(String key, String msg, TypedActorFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
  }

  public String failure(String key, String msg, NestedTransactionalTypedActor nested, TypedActorFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    nested.failure(key, msg, failer);
    return msg;
  }

  @Override
  public void preRestart(Throwable e) {
    System.out.println("################ PRE RESTART");
  }

  @Override
  public void postRestart(Throwable e) {
    System.out.println("################ POST RESTART");
  }
}
