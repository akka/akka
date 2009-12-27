/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.Config;
import se.scalablesolutions.akka.actor.ActiveObject;
import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import se.scalablesolutions.akka.remote.RemoteNode;

import junit.framework.TestCase;

public class RemoteInMemoryStateTest extends TestCase {
  static String messageLog = "";

  static {
    new Thread(new Runnable() {
       public void run() {
         RemoteNode.start();
       }
    }).start();
    try { Thread.currentThread().sleep(1000);  } catch (Exception e) {}
      Config.config();
  }
  final ActiveObjectConfigurator conf = new ActiveObjectConfigurator();

  protected void tearDown() {
    conf.stop();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    InMemFailer failer = ActiveObject.newRemoteInstance(InMemFailer.class, 1000, "localhost", 9999); //conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getVectorState());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setVectorState("init"); // set init state
    InMemFailer failer = ActiveObject.newRemoteInstance(InMemFailer.class, 1000, "localhost", 9999); //conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getVectorState()); // check that state is == init state
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
    InMemStateful stateful = ActiveObject.newRemoteInstance(InMemStateful.class, 1000, "localhost", 9999);
    stateful.init();
    stateful.setRefState("init"); // set init state
    InMemFailer failer = ActiveObject.newRemoteInstance(InMemFailer.class, 1000, "localhost", 9999); //conf.getInstance(InMemFailer.class);
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
  // } // expected
  // assertEquals("init", stateful.getState("stateful")); // check that state is
  // // == init state
  // // assertEquals("init", clasher.getState("clasher")); // check that state
  // is
  // // == init state
  // }
}