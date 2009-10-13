package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.state.TransactionalMap;

public class PersistentClasher {
  private TransactionalMap state = new se.scalablesolutions.akka.state.CassandraPersistentMap();

  public String getState(String key) {
    return (String)state.get(key).get();
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void clash() {
    state.put("clasher", "was here");
    // spend some time here

    // FIXME: this statement gives me this error:
    // se.scalablesolutions.akka.kernel.ActiveObjectException:
    // Unexpected message [!(scala.actors.Channel@c2b2f6,ResultOrFailure[Right(null)])]
    // to
    // [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    // [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    // try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}