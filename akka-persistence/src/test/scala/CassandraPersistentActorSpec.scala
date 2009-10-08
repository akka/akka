package se.scalablesolutions.akka.state

import akka.actor.Actor
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

import junit.framework.TestCase
import dispatch._

import org.junit.{Test, Before}
import org.junit.Assert._

case class GetMapState(key: String)
case object GetVectorState
case object GetVectorSize
case object GetRefState

case class SetMapState(key: String, value: String)
case class SetVectorState(key: String)
case class SetRefState(key: String)
case class Success(key: String, value: String)
case class Failure(key: String, value: String, failer: Actor)

case class SetMapStateOneWay(key: String, value: String)
case class SetVectorStateOneWay(key: String)
case class SetRefStateOneWay(key: String)
case class SuccessOneWay(key: String, value: String)
case class FailureOneWay(key: String, value: String, failer: Actor)

class CassandraPersistentActor extends Actor {
  timeout = 100000
  makeTransactionRequired

  private lazy val mapState: PersistentMap = PersistentState.newMap(CassandraStorageConfig())
  private lazy val vectorState: PersistentVector = PersistentState.newVector(CassandraStorageConfig())
  private lazy val refState: PersistentRef = PersistentState.newRef(CassandraStorageConfig())

  def receive: PartialFunction[Any, Unit] = {
    case GetMapState(key) =>
      reply(mapState.get(key).get)
    case GetVectorSize =>
      reply(vectorState.length.asInstanceOf[AnyRef])
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

@serializable class PersistentFailerActor extends Actor {
  makeTransactionRequired
  def receive: PartialFunction[Any, Unit] = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class CassandraPersistentActorSpec extends TestCase {

  @Test
  def testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new CassandraPersistentActor
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def testMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new CassandraPersistentActor
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
    val stateful = new CassandraPersistentActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals(2, (stateful !! GetVectorSize).get)
  }

  @Test
  def testVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new CassandraPersistentActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = new PersistentFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals(1, (stateful !! GetVectorSize).get)
  }

  @Test
  def testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new CassandraPersistentActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetRefState).get)
  }

  @Test
  def testRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new CassandraPersistentActor
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
