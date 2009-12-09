package se.scalablesolutions.akka.state

import se.scalablesolutions.akka.actor.Actor

import junit.framework.TestCase

import org.junit.Test
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

  private lazy val mapState = CassandraStorage.newMap
  private lazy val vectorState = CassandraStorage.newVector
  private lazy val refState = CassandraStorage.newRef

  def receive = {
    case GetMapState(key) =>
      reply(mapState.get(key.getBytes("UTF-8")).get)
    case GetVectorSize =>
      reply(vectorState.length.asInstanceOf[AnyRef])
    case GetRefState =>
      reply(refState.get.get)
    case SetMapState(key, msg) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      reply(msg)
    case SetVectorState(msg) =>
      vectorState.add(msg.getBytes("UTF-8"))
      reply(msg)
    case SetRefState(msg) =>
      refState.swap(msg.getBytes("UTF-8"))
      reply(msg)
    case Success(key, msg) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      vectorState.add(msg.getBytes("UTF-8"))
      refState.swap(msg.getBytes("UTF-8"))
      reply(msg)
    case Failure(key, msg, failer) =>
      mapState.put(key.getBytes("UTF-8"), msg.getBytes("UTF-8"))
      vectorState.add(msg.getBytes("UTF-8"))
      refState.swap(msg.getBytes("UTF-8"))
      failer !! "Failure"
      reply(msg)
  }
}

@serializable class PersistentFailerActor extends Actor {
  makeTransactionRequired
  def receive = {
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
    val result: Array[Byte] = (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get
    assertEquals("new state", new String(result, 0, result.length, "UTF-8"))
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
    val result: Array[Byte] = (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get
    assertEquals("init", new String(result, 0, result.length, "UTF-8")) // check that state is == init state
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
    val result: Array[Byte] = (stateful !! GetRefState).get
    assertEquals("new state", new String(result, 0, result.length, "UTF-8"))
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
    val result: Array[Byte] = (stateful !! GetRefState).get
    assertEquals("init",  new String(result, 0, result.length, "UTF-8")) // check that state is == init state
  }
}
