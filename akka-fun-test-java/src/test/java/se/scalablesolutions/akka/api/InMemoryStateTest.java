/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import junit.framework.TestCase;

import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.actor.*;
import se.scalablesolutions.akka.kernel.Kernel;

public class InMemoryStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectManager conf = new ActiveObjectManager();


  protected void setUp() {
    se.scalablesolutions.akka.kernel.Kernel$.MODULE$.config();
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[]{
            // FIXME: remove string-name, add ctor to only accept target class
            new Component(InMemStateful.class,
                new LifeCycle(new Permanent(), 1000),
                              //new RestartCallbacks("preRestart", "postRestart")),
            10000),
            new Component(InMemFailer.class,
                new LifeCycle(new Permanent(), 1000),
            10000)
        }).inject().supervise();
  }

    protected void tearDown() {
    conf.stop();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // to trigger commit
    assertEquals("new state", stateful.getVectorState());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getVectorState()); // check that state is == init state
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // to trigger commit
    assertEquals("new state", stateful.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setRefState("init"); // set init state
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getRefState()); // check that state is == init state
  }
  /*
   public void testNestedNonTransactionalMethodHangs() {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.thisMethodHangs("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }
  */
  // public void testShouldRollbackStateForStatefulServerInCaseOfMessageClash()
  // {
  // InMemStateful stateful = conf.getInstance(InMemStateful.class);
  // stateful.setState("stateful", "init"); // set init state
  //
  // InMemClasher clasher = conf.getInstance(InMemClasher.class);
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

/*
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
    // Unexpected message [!(scala.actors.Channel@c2b2f6,ResultOrFailure[Right(null)])]
    // to
    // [GenericServer[se.scalablesolutions.akka.api.StatefulImpl]] from
    // [GenericServer[se.scalablesolutions.akka.api.ClasherImpl]]]
    // try { Thread.sleep(1000); } catch (InterruptedException e) {}
  }
}
*/
