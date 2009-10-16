package se.scalablesolutions.akka.actor

import junit.framework.TestCase

import org.junit.{Test, Before}
import org.junit.Assert._
import se.scalablesolutions.akka.state.{TransactionalState, TransactionalMap, TransactionalRef, TransactionalVector}

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

class InMemStatefulActor extends Actor {
  timeout = 100000
  makeTransactionRequired
  //dispatcher = se.scalablesolutions.akka.dispatch.Dispatchers.newThreadBasedDispatcher(this)
  private lazy val mapState: TransactionalMap[String, String] = TransactionalState.newMap[String, String]
  private lazy val vectorState: TransactionalVector[String] = TransactionalState.newVector[String]
  private lazy val refState: TransactionalRef[String] = TransactionalState.newRef[String]

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

    case SetMapStateOneWay(key, msg) =>
      mapState.put(key, msg)
    case SetVectorStateOneWay(msg) =>
      vectorState.add(msg)
    case SetRefStateOneWay(msg) =>
      refState.swap(msg)
    case SuccessOneWay(key, msg) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
    case FailureOneWay(key, msg, failer) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      failer ! "Failure"
  }
}

@serializable
class InMemFailerActor extends Actor {
  makeTransactionRequired
  def receive: PartialFunction[Any, Unit] = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}
                                                        
class InMemoryActorSpec extends TestCase {

  /*
  @Test
  def testOneWayMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetMapStateOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assertEquals("new state", (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }
  */
  @Test
  def testMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }
  /*
  @Test
  def testOneWayMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    val failer = new InMemFailerActor
    failer.start
    stateful ! SetMapStateOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    Thread.sleep(1000)
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assertEquals("init", (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }
  */
  @Test
  def testMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }
  /*
  @Test
  def testOneWayVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assertEquals(2, (stateful !! GetVectorSize).get)
  }
  */
  @Test
  def testVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals(2, (stateful !! GetVectorSize).get)
  }
  /*
  @Test
  def testOneWayVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = new InMemFailerActor
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assertEquals(1, (stateful !! GetVectorSize).get)
  }
  */
  @Test
  def testVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals(1, (stateful !! GetVectorSize).get)
  }
  /*
  @Test
  def testOneWayRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assertEquals("new state", (stateful !! GetRefState).get)
  }
  */
  @Test
  def testRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assertEquals("new state", (stateful !! GetRefState).get)
  }
  /*
  @Test
  def testOneWayRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = new InMemFailerActor
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assertEquals("init", (stateful !! GetRefState).get) // check that state is == init state
  }
  */
  @Test
  def testRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assertEquals("init", (stateful !! GetRefState).get) // check that state is == init state
  }
}
