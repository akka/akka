/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.TransactionalMap;
import se.scalablesolutions.akka.kernel.InMemoryTransactionalMap;

import junit.framework.TestCase;

public class InMemoryStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  protected void setUp() {
    conf.configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[] { 
          new Component("inmem-stateful", InMemStateful.class, InMemStatefulImpl.class, new LifeCycle(new Permanent(), 1000), 10000000),
          new Component("inmem-failer", InMemFailer.class, InMemFailerImpl.class, new LifeCycle(new Permanent(), 1000), 1000),
          new Component("inmem-clasher", InMemClasher.class, InMemClasherImpl.class, new LifeCycle(new Permanent(), 1000), 100000) 
          }).inject().supervise();
  }
  
  protected void tearDown() {
    conf.stop();
  }
  
  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = conf.getActiveObject("inmem-stateful");
    stateful.setState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactional
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // to trigger commit
    assertEquals("new state", stateful.getState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

   public void testShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = conf.getActiveObject("inmem-stateful");
    stateful.setState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    InMemFailer failer = conf.getActiveObject("inmem-failer");
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactional method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }
  /*
  */
  // public void testShouldRollbackStateForStatefulServerInCaseOfMessageClash()
  // {
  // InMemStateful stateful = conf.getActiveObject(InMemStateful.class);
  // stateful.setState("stateful", "init"); // set init state
  //
  // InMemClasher clasher = conf.getActiveObject(InMemClasher.class);
  // clasher.setState("clasher", "init"); // set init state
  //
  // // try {
  // // stateful.clashOk("stateful", "new state", clasher);
  // // } catch (RuntimeException e) { } // expected
  // // assertEquals("new state", stateful.getState("stateful")); // check that
  // // state is == init state
  // // assertEquals("was here", clasher.getState("clasher")); // check that
  // // state is == init state
  //
  // try {
  // stateful.clashNotOk("stateful", "new state", clasher);
  // fail("should have thrown an exception");
  // } catch (RuntimeException e) {
  // System.out.println(e);
  // } // expected
  // assertEquals("init", stateful.getState("stateful")); // check that state is
  // // == init state
  // // assertEquals("init", clasher.getState("clasher")); // check that state
  // is
  // // == init state
  // }
}

interface InMemStateful {
  // transactional
  @transactional
  public void success(String key, String msg);

  @transactional
  public void failure(String key, String msg, InMemFailer failer);

  @transactional
  public void clashOk(String key, String msg, InMemClasher clasher);

  @transactional
  public void clashNotOk(String key, String msg, InMemClasher clasher);

  // non-transactional
  public String getState(String key);

  public void setState(String key, String value);
}

class InMemStatefulImpl implements InMemStateful {
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

  public void clashOk(String key, String msg, InMemClasher clasher) {
    state.put(key, msg);
    clasher.clash();
  }

  public void clashNotOk(String key, String msg, InMemClasher clasher) {
    state.put(key, msg);
    clasher.clash();
    this.success("clash", "clash");
  }
}

interface InMemFailer {
  public void fail();
}

class InMemFailerImpl implements InMemFailer {
  public void fail() {
    throw new RuntimeException("expected");
  }
}

interface InMemClasher {
  public void clash();

  public String getState(String key);

  public void setState(String key, String value);
}

class InMemClasherImpl implements InMemClasher {
  @state
  private TransactionalMap<String, Object> state = new InMemoryTransactionalMap<String, Object>();

  public String getState(String key) {
    return (String) state.get(key).get();
  }

  public void setState(String key, String msg) {
    state.put(key, msg);
  }

  public void clash() {
    state.put("clasher", "was here");
    // spend some time here
    // for (long i = 0; i < 1000000000; i++) {
    // for (long j = 0; j < 10000000; j++) {
    // j += i;
    // }
    // }

    // FIXME: this statement gives me this error:
    // se.scalablesolutions.akka.kernel.ActiveObjectException:
    // Unexpected message [!(scala.actors.Channel@c2b2f6,ErrRef[Right(null)])]
    // to
    // [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    // [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    // try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}
