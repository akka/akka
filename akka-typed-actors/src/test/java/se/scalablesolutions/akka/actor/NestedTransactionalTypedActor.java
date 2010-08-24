package se.scalablesolutions.akka.actor;

public interface NestedTransactionalTypedActor {
  public String getMapState(String key);
  public String getVectorState();
  public String getRefState();
  public void setMapState(String key, String msg);
  public void setVectorState(String msg);
  public void setRefState(String msg);
  public void success(String key, String msg);
  public String failure(String key, String msg, TypedActorFailer failer);
}
