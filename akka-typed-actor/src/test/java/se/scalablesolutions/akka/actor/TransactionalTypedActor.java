package akka.actor;

public interface TransactionalTypedActor {
  public String getMapState(String key);
  public String getVectorState();
  public String getRefState();
  public void setMapState(String key, String msg);
  public void setVectorState(String msg);
  public void setRefState(String msg);
  public void success(String key, String msg);
  public void success(String key, String msg, NestedTransactionalTypedActor nested);
  public String failure(String key, String msg, TypedActorFailer failer);
  public String failure(String key, String msg, NestedTransactionalTypedActor nested, TypedActorFailer failer);
}
