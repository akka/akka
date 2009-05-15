/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.*;
import se.scalablesolutions.akka.kernel.config.JavaConfig.AllForOne;
import se.scalablesolutions.akka.kernel.config.JavaConfig.Component;
import se.scalablesolutions.akka.kernel.config.JavaConfig.LifeCycle;
import se.scalablesolutions.akka.kernel.config.JavaConfig.Permanent;
import se.scalablesolutions.akka.kernel.config.JavaConfig.RestartStrategy;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.Kernel;
import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.CassandraPersistentTransactionalMap;

import junit.framework.TestCase;

public class PersistentStateTest extends TestCase {
  static String messageLog = "";

  static {
    System.setProperty("storage-config", "config");
    Kernel.startCassandra();
  }
  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  protected void setUp() {
    conf.configureActiveObjects(
        new JavaConfig.RestartStrategy(new JavaConfig.AllForOne(), 3, 5000),
        new Component[] {
          new Component("persistent-stateful", PersistentStateful.class, PersistentStatefulImpl.class, new LifeCycle(new Permanent(), 1000), 10000000),
          new Component("persistent-failer", PersistentFailer.class, PersistentFailerImpl.class, new LifeCycle(new Permanent(), 1000), 1000),
          new Component("persistent-clasher", PersistentClasher.class, PersistentClasherImpl.class, new LifeCycle(new Permanent(), 1000), 100000) 
        }).supervise();
  }

  protected void tearDown() {
    conf.stop();
  }
  
  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject("persistent-stateful");
    stateful.setState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactional
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // to trigger commit
    assertEquals("new state", stateful.getState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getActiveObject("persistent-stateful");
    stateful.setState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    PersistentFailer failer = conf.getActiveObject("persistent-failer");
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactional method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
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
  private TransactionalMap state = new CassandraPersistentTransactionalMap(this);

  public String getState(String key) {
    return (String)state.get(key).get();
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
  private TransactionalMap state = new CassandraPersistentTransactionalMap(this);

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
    // Unexpected message [!(scala.actors.Channel@c2b2f6,ErrRef[Right(null)])]
    // to
    // [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    // [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    // try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}