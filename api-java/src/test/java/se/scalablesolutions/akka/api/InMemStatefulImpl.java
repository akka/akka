package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.state;
import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.InMemoryTransactionalMap;

public class InMemStatefulImpl implements InMemStateful {
  @state
  private TransactionalMap<String, String> state = new InMemoryTransactionalMap<String, String>();

  public String getState(String key) {
    return state.get(key).get();
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void success(String key, String msg) {
    state.put(key, msg);
  }

  public void failure(String key, String msg, InMemFailer failer) {
    state.put(key, msg);
    failer.fail();
  }

  /*
  public void clashOk(String key, String msg, InMemClasher clasher) {
    state.put(key, msg);
    clasher.clash();
  }

  public void clashNotOk(String key, String msg, InMemClasher clasher) {
    state.put(key, msg);
    clasher.clash();
    this.success("clash", "clash");
  }
  */
}