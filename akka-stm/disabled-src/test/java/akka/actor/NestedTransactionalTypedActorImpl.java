package akka.actor;

import akka.actor.*;
import akka.stm.*;

public class NestedTransactionalTypedActorImpl extends TypedTransactor implements NestedTransactionalTypedActor {
  private TransactionalMap<String, String> mapState;
  private TransactionalVector<String> vectorState;
  private Ref<String> refState;
  private boolean isInitialized = false;

  @Override
  public void preStart() {
    if (!isInitialized) {
      mapState = new TransactionalMap();
      vectorState = new TransactionalVector();
      refState = new Ref();
      isInitialized = true;
    }
  }

  public String getMapState(String key) {
    return (String) mapState.get(key).get();
  }

  public String getVectorState() {
    return (String) vectorState.last();
  }

  public String getRefState() {
    return (String) refState.get();
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

  public String failure(String key, String msg, TypedActorFailer failer) {
    mapState.put(key, msg);
    vectorState.add(msg);
    refState.swap(msg);
    failer.fail();
    return msg;
  }
}
