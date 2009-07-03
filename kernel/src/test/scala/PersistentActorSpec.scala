package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

import junit.framework.TestCase
import kernel.Kernel

import kernel.reactor._

import kernel.state.{CassandraStorageConfig, TransactionalState}
import org.junit.{Test, Before}
import org.junit.Assert._

class PersistentActor extends Actor {
  makeTransactional
  private val mapState = TransactionalState.newPersistentMap(CassandraStorageConfig())
  private val vectorState = TransactionalState.newPersistentVector(CassandraStorageConfig())
  private val refState = TransactionalState.newPersistentRef(CassandraStorageConfig())

  def receive: PartialFunction[Any, Unit] = {
    case GetMapState(key) =>
      reply(mapState.get(key).get)
    case GetVectorState =>
      reply(vectorState.last)
    case GetRefState =>
      reply(refState.get.get)
    case SetMapState(key, msg) =>
      mapState.put(key, msg)
      reply(msg)
    case SetVectorState(msg) =>
      vectorState.add(msg)
      reply(msg)
    case SetRefState(msg) =>
      refState.swap(msg)
      reply(msg)
    case Success(key, msg) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      reply(msg)
    case Failure(key, msg, failer) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      failer !! "Failure"
      reply(msg)
  }
}

class PersistentFailerActor extends Actor {
  makeTransactional
  def receive: PartialFunction[Any, Unit] = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

object PersistenceManager {
  @volatile var isRunning = false
  def init = if (!isRunning) {
    System.setProperty("storage-config", "config")
    Kernel.startCassandra
    isRunning = true
  }
}
class PersistentActorSpec extends TestCase {
  PersistenceManager.init

  @Test
  def testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def testMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = new PersistentFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetVectorState).get)
  }

  @Test
  def testVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = new PersistentFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetVectorState).get) // check that state is == init state
  }

  @Test
  def testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetRefState).get)
  }

  @Test
  def testRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new PersistentActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = new PersistentFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetRefState).get) // check that state is == init state
  }
}