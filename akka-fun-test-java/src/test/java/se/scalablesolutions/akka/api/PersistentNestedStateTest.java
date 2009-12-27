/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.config.*;
import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import static se.scalablesolutions.akka.config.JavaConfig.*;
import se.scalablesolutions.akka.actor.*;
	import se.scalablesolutions.akka.Kernel;

import junit.framework.TestCase;

public class PersistentNestedStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectConfigurator conf = new ActiveObjectConfigurator();

  protected void setUp() {
      PersistenceManager.init();
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000, new Class[] {Exception.class}),
        new Component[]{
            new Component(PersistentStateful.class, new LifeCycle(new Permanent()), 10000000),
            new Component(PersistentStatefulNested.class, new LifeCycle(new Permanent()), 10000000),
            new Component(PersistentFailer.class, new LifeCycle(new Permanent()), 1000)
            //new Component("inmem-clasher", InMemClasher.class, InMemClasherImpl.class, new LifeCycle(new Permanent()), 100000)
        }).inject().supervise();
  }

  protected void tearDown() {
    conf.stop();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    nested.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactional
    assertEquals("new state", nested.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    PersistentFailer failer = conf.getInstance(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactional method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
    assertEquals("init", nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    stateful.setVectorState("init"); // set init state
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    nested.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactional
    assertEquals(2, stateful.getVectorLength()); // BAD: keeps one element since last test
    assertEquals(2, nested.getVectorLength());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    stateful.setVectorState("init"); // set init state
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    nested.setVectorState("init"); // set init state
    PersistentFailer failer = conf.getInstance(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactional method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals(1, stateful.getVectorLength());
    assertEquals(1, nested.getVectorLength());
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    stateful.setRefState("init"); // set init state
    nested.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactional
    assertEquals("new state", stateful.getRefState());
    assertEquals("new state", nested.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getInstance(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getInstance(PersistentStatefulNested.class);
    stateful.setRefState("init"); // set init state
    nested.setRefState("init"); // set init state
    PersistentFailer failer = conf.getInstance(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactional method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getRefState()); // check that state is == init state
    assertEquals("init", nested.getRefState()); // check that state is == init state
  }
}
