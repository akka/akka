/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.actor.*;
import se.scalablesolutions.akka.kernel.Kernel;

import junit.framework.TestCase;

public class PersistentNestedStateTest extends TestCase {
  static String messageLog = "";

  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();
  final private ActiveObjectFactory factory = new ActiveObjectFactory();

  static {
      se.scalablesolutions.akka.kernel.Kernel$.MODULE$.config();
    System.setProperty("storage-config", "config");
    Kernel.startCassandra();
  }
  
  protected void setUp() {
    conf.configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[]{
            // FIXME: remove string-name, add ctor to only accept target class
            new Component(PersistentStateful.class, new LifeCycle(new Permanent(), 1000), 10000000),
            new Component(PersistentStatefulNested.class, new LifeCycle(new Permanent(), 1000), 10000000),
            new Component(PersistentFailer.class, new LifeCycle(new Permanent(), 1000), 1000)
            //new Component("inmem-clasher", InMemClasher.class, InMemClasherImpl.class, new LifeCycle(new Permanent(), 1000), 100000)
        }).inject().supervise();
  }

  protected void tearDown() {
    conf.stop();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    nested.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    assertEquals("new state", nested.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
    assertEquals("init", nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setVectorState("init"); // set init state
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    nested.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    assertEquals(3, stateful.getVectorLength()); // BAD: keeps one element since last test
    assertEquals(3, nested.getVectorLength());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setVectorState("init"); // set init state
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    nested.setVectorState("init"); // set init state
    PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals(1, stateful.getVectorLength());
    assertEquals(1, nested.getVectorLength());
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    stateful.setRefState("init"); // set init state
    nested.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    assertEquals("new state", stateful.getRefState());
    assertEquals("new state", nested.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    PersistentStatefulNested nested = conf.getActiveObject(PersistentStatefulNested.class);
    stateful.setRefState("init"); // set init state
    nested.setRefState("init"); // set init state
    PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getRefState()); // check that state is == init state
    assertEquals("init", nested.getRefState()); // check that state is == init state
  }
}
