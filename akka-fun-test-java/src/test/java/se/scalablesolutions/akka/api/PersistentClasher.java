package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.persistence.common.*;
import se.scalablesolutions.akka.persistence.cassandra.*;
import se.scalablesolutions.akka.actor.annotation.inittransactionalstate;

public class PersistentClasher {
  private PersistentMap state;

  @inittransactionalstate
  public void init() {
    state = CassandraStorage.newMap();
  }
  
  public String getState(String key) {
    return (String)state.get(key).get();
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void clash() {
    state.put("clasher", "was here");
  }
}