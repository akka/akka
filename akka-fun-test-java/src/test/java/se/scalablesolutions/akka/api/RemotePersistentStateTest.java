/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.config.*;
import static se.scalablesolutions.akka.config.JavaConfig.*;

import junit.framework.TestCase;

public class RemotePersistentStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectConfigurator conf = new ActiveObjectConfigurator();

  protected void setUp() {
    PersistenceManager.init();
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000, new Class[]{Exception.class}),
        new Component[] {
          new Component(PersistentStateful.class, new LifeCycle(new Permanent()), 1000000, new RemoteAddress("localhost", 9999)),
          new Component(PersistentFailer.class, new LifeCycle(new Permanent()), 1000000, new RemoteAddress("localhost", 9999))
        }).supervise();
  }

  protected void tearDown() {
    conf.stop();
  }

  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }
  
  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
   stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
   PersistentFailer failer = conf.getInstance(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "MapShouldRollBack", failer); // call failing transactionrequired method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
 }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    int init = stateful.getVectorLength();
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "VectorShouldNotRollback"); // transactionrequired
    assertEquals(init + 1, stateful.getVectorLength());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
   int init = stateful.getVectorLength();
   PersistentFailer failer = conf.getInstance(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals(init, stateful.getVectorLength());
 }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    stateful.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactionrequired
    assertEquals("new state", stateful.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
   stateful.setRefState("init"); // set init state
   PersistentFailer failer = conf.getInstance(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactionrequired method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals("init", stateful.getRefState()); // check that state is == init state
 }
 
}