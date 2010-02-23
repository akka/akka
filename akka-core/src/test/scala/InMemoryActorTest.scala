package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

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
  timeout = 5000
  makeTransactionRequired

  private lazy val mapState = TransactionalState.newMap[String, String]
  private lazy val vectorState = TransactionalState.newVector[String]
  private lazy val refState = TransactionalState.newRef[String]

  def receive = {
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
  def receive = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}
                                                        
class InMemoryActorTest extends JUnitSuite {
  import Actor.Sender.Self

  @Test
  def shouldOneWayMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetMapStateOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assert("new state" === (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def shouldMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def shouldOneWayMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    val failer = new InMemFailerActor
    failer.start
    stateful ! SetMapStateOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    Thread.sleep(1000)
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assert("init" === (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def shouldMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def shouldOneWayVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assert(2 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert(2 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldOneWayVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = new InMemFailerActor
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assert(1 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert(1 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldOneWayRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    Thread.sleep(1000)
    assert("new state" === (stateful !! GetRefState).get)
  }

  @Test
  def shouldRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful !! GetRefState).get)
  }

  @Test
  def shouldOneWayRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = new InMemFailerActor
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    Thread.sleep(1000)
    assert("init" === (stateful !! (GetRefState, 1000000)).get) // check that state is == init state
  }

  @Test
  def shouldRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = new InMemStatefulActor
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = new InMemFailerActor
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful !! GetRefState).get) // check that state is == init state
  }
}
