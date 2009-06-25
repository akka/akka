/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.api;

import se.scalablesolutions.akka.annotation.*;
import se.scalablesolutions.akka.kernel.config.*;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;
import se.scalablesolutions.akka.kernel.Kernel;
import se.scalablesolutions.akka.kernel.state.TransactionalMap;
import se.scalablesolutions.akka.kernel.state.CassandraPersistentTransactionalMap;
import se.scalablesolutions.akka.kernel.actor.*;
import se.scalablesolutions.akka.kernel.nio.NettyServer;
import junit.framework.TestCase;

public class RemotePersistentStateTest extends TestCase {
  static String messageLog = "";

  static {
    System.setProperty("storage-config", "config");
    Kernel.startCassandra();
    new Thread(new Runnable() {
       public void run() {
         NettyServer server = new NettyServer();
         server.connect();
       }
    }).start();
  }
  final private ActiveObjectGuiceConfiguratorForJava conf = new ActiveObjectGuiceConfiguratorForJava();

  final private ActiveObjectFactory factory = new ActiveObjectFactory();

  protected void setUp() {
    conf.configureActiveObjects(
        new RestartStrategy(new AllForOne(), 3, 5000),
        new Component[] {
          new Component(PersistentStateful.class, new LifeCycle(new Permanent(), 1000), 10000000),
          new Component(PersistentFailer.class, new LifeCycle(new Permanent(), 1000), 1000)
          //new Component(PersistentClasher.class, new LifeCycle(new Permanent(), 1000), 100000)
        }).supervise();
  }

  protected void tearDown() {
    conf.stop();
  }

  public void testShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactional
    assertEquals("new state", stateful.getMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess"));
  }

  public void testMapShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
   stateful.setMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init"); // set init state
   PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactional method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals("init", stateful.getMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")); // check that state is == init state
 }

  public void testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setVectorState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactional
    assertEquals("init", stateful.getVectorState(0));
    assertEquals("new state", stateful.getVectorState(1));
  }

  public void testVectorShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
   stateful.setVectorState("init"); // set init state
   PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactional method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals("init", stateful.getVectorState(0)); // check that state is == init state
 }

  public void testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess() {
    PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
    stateful.setRefState("init"); // set init state
    stateful.success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state"); // transactional
    assertEquals("new state", stateful.getRefState());
  }

  public void testRefShouldRollbackStateForStatefulServerInCaseOfFailure() {
   PersistentStateful stateful = conf.getActiveObject(PersistentStateful.class);
   stateful.setRefState("init"); // set init state
   PersistentFailer failer = conf.getActiveObject(PersistentFailer.class);
   try {
     stateful.failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer); // call failing transactional method
     fail("should have thrown an exception");
   } catch (RuntimeException e) {
   } // expected
   assertEquals("init", stateful.getRefState()); // check that state is == init state
 }
}