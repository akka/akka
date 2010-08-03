/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.config.*;
import se.scalablesolutions.akka.config.Config;
import se.scalablesolutions.akka.config.TypedActorConfigurator;
import static se.scalablesolutions.akka.config.JavaConfig.*;
import se.scalablesolutions.akka.actor.*;
import junit.framework.TestCase;

public class InMemNestedStateTest extends TestCase {
  static String messageLog = "";

  final private TypedActorConfigurator conf = new TypedActorConfigurator();

  public InMemNestedStateTest() {
    conf.configure(
        new RestartStrategy(new AllForOne(), 3, 5000, new Class[]{Exception.class}),
        new Component[]{
            new Component(InMemStateful.class, new LifeCycle(new Permanent()), 10000000),
            new Component(InMemStatefulNested.class, new LifeCycle(new Permanent()), 10000000),
            new Component(InMemFailer.class, new LifeCycle(new Permanent()), 1000)
            //new Component("inmem-clasher", InMemClasher.class, InMemClasherImpl.class, new LifeCycle(new Permanent()), 100000)
        }).supervise();
    Config.config();
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.init();
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.init();
  }

  public void testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    Thread.sleep(100);
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    Thread.sleep(100);
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
    Thread.sleep(100);
    assertEquals("new state", nested.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
  }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    Thread.sleep(100);
    nested.setVectorState("init"); // set init state
    Thread.sleep(100);
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    Thread.sleep(100);
    assertEquals("new state", stateful.getVectorState());
    Thread.sleep(100);
    assertEquals("new state", nested.getVectorState());
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    stateful.setVectorState("init"); // set init state
    Thread.sleep(100);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    nested.setVectorState("init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getVectorState()); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getVectorState()); // check that state is == init state
  }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() throws Exception {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    stateful.setRefState("init"); // set init state
    Thread.sleep(100);
    nested.setRefState("init"); // set init state
    Thread.sleep(100);
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state", nested); // transactionrequired
    Thread.sleep(100);
    assertEquals("new state", stateful.getRefState());
    Thread.sleep(100);
    assertEquals("new state", nested.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() throws InterruptedException {
    InMemStateful stateful = conf.getInstance(InMemStateful.class);
    InMemStatefulNested nested = conf.getInstance(InMemStatefulNested.class);
    stateful.setRefState("init"); // set init state
    Thread.sleep(100);
    nested.setRefState("init"); // set init state
    Thread.sleep(100);
    InMemFailer failer = conf.getInstance(InMemFailer.class);
    try {
      stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", nested, failer); // call failing transactionrequired method
      Thread.sleep(100);
      fail("should have thrown an exception");
    } catch (RuntimeException e) {
    } // expected
    assertEquals("init", stateful.getRefState()); // check that state is == init state
    Thread.sleep(100);
    assertEquals("init", nested.getRefState()); // check that state is == init state
  }
}
