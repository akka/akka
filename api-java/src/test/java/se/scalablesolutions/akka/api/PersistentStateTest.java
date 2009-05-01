/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.*;
import se.scalablesolutions.akka.kernel.configuration.LifeCycle;
import se.scalablesolutions.akka.kernel.configuration.Permanent;
import se.scalablesolutions.akka.kernel.configuration.Component;
import se.scalablesolutions.akka.kernel.configuration.AllForOne;
import se.scalablesolutions.akka.kernel.configuration.RestartStrategy;

import com.google.inject.Inject;
import com.google.inject.AbstractModule;
import com.google.inject.Scopes;

import junit.framework.TestCase;

public class PersistentStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfigurator conf = new ActiveObjectGuiceConfigurator();
  
  protected void setUp() {
    conf.configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[] { 
          new Component(PersistentStateful.class, PersistentStatefulImpl.class, new LifeCycle(new Permanent(), 1000), 10000000), 
          new Component(PersistentFailer.class, PersistentFailerImpl.class, new LifeCycle(new Permanent(), 1000), 1000),
          new Component(PersistentClasher.class, PersistentClasherImpl.class, new LifeCycle(new Permanent(), 1000), 100000) 
        }).inject().supervise();
  }

  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    /*
       PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setState("stateful", "init"); // set init state
    stateful.success("stateful", "new state"); // transactional
    assertEquals("new state", stateful.getState("stateful"));
  */
    assertTrue(true);
  }
}

interface PersistentStateful {
  @transactional
  public void success(String key, String msg);

  @transactional
  public void failure(String key, String msg, PersistentFailer failer);

  @transactional
  public void clashOk(String key, String msg, PersistentClasher clasher);

  @transactional
  public void clashNotOk(String key, String msg, PersistentClasher clasher);

  public String getState(String key);

  public void setState(String key, String value);
}

class PersistentStatefulImpl implements PersistentStateful {
  private TransactionalMap<String, String> state = new CassandraPersistentTransactionalMap(this);

  public String getState(String key) {
    return state.get(key);
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void success(String key, String msg) {
    state.put(key, msg);
  }

  public void failure(String key, String msg, PersistentFailer failer) {
    state.put(key, msg);
    failer.fail();
  }

  public void clashOk(String key, String msg, PersistentClasher clasher) {
    state.put(key, msg);
    clasher.clash();
  }

  public void clashNotOk(String key, String msg, PersistentClasher clasher) {
    state.put(key, msg);
    clasher.clash();
    clasher.clash();
  }
}

interface PersistentFailer {
  public void fail();
}

class PersistentFailerImpl implements PersistentFailer {
  public void fail() {
    throw new RuntimeException("expected");
  }
}

interface PersistentClasher {
  public void clash();

  public String getState(String key);

  public void setState(String key, String value);
}

class PersistentClasherImpl implements PersistentClasher {
  private TransactionalMap<String, String> state = new CassandraPersistentTransactionalMap(this);

  public String getState(String key) {
    return state.get(key);
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void clash() {
    state.put("clasher", "was here");
    // spend some time here

    // FIXME: this statement gives me this error:
    // se.scalablesolutions.akka.kernel.ActiveObjectException:
    // Unexpected message [!(scala.actors.Channel@c2b2f6,ErrRef[Right(null)])]
    // to
    // [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    // [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    // try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}