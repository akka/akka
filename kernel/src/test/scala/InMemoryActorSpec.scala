package se.scalablesolutions.akka.kernel.actor

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

import kernel.state.TransactionalState
import kernel.reactor._

import org.junit.{Test, Before}
import org.junit.Assert._

case class SetMapState(key: String, value: String)
case class SetVectorState(key: String)
case class SetRefState(key: String)
case class GetMapState(key: String)
case object GetVectorState
case object GetRefState
case class Success(key: String, value: String)
case class Failure(key: String, value: String, failer: Actor)

class InMemStatefulActor extends Actor {
  makeTransactional 
  private val mapState = TransactionalState.newInMemoryMap[String, String]
  private val vectorState = TransactionalState.newInMemoryVector[String]
  private val refState = TransactionalState.newInMemoryRef[String]

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

class InMemFailerActor extends Actor {
  makeTransactional
  def receive: PartialFunction[Any, Unit] = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class InMemoryActorSpec {
  @Test
  def testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactional
    assertEquals("new state", (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def testMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactional method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactional
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // to trigger commit
    assertEquals("new state", (stateful !! GetVectorState).get)
  }

  @Test
  def testVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactional method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetVectorState).get) // check that state is == init state
  }

  @Test
  def testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactional
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // to trigger commit
    assertEquals("new state", (stateful !! GetRefState).get)
  }

  @Test
  def testRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactional method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetRefState).get) // check that state is == init state
  }
}