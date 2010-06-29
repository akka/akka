package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.stm.{Ref, TransactionalMap, TransactionalVector}
import Actor._

object TransactorSpec {
  case class GetMapState(key: String)
  case object GetVectorState
  case object GetVectorSize
  case object GetRefState

  case class SetMapState(key: String, value: String)
  case class SetVectorState(key: String)
  case class SetRefState(key: String)
  case class Success(key: String, value: String)
  case class Failure(key: String, value: String, failer: ActorRef)

  case class SetMapStateOneWay(key: String, value: String)
  case class SetVectorStateOneWay(key: String)
  case class SetRefStateOneWay(key: String)
  case class SuccessOneWay(key: String, value: String)
  case class FailureOneWay(key: String, value: String, failer: ActorRef)

  case object GetNotifier
}
import TransactorSpec._

class StatefulTransactor(expectedInvocationCount: Int) extends Transactor {
  def this() = this(0)
  self.timeout = 5000

  val notifier = new CountDownLatch(expectedInvocationCount)

  private lazy val mapState = TransactionalMap[String, String]()
  private lazy val vectorState = TransactionalVector[String]()
  private lazy val refState = Ref[String]()

  def receive = {
    case GetNotifier =>
      self.reply(notifier)
    case GetMapState(key) =>
      self.reply(mapState.get(key).get)
      notifier.countDown
    case GetVectorSize =>
      self.reply(vectorState.length.asInstanceOf[AnyRef])
      notifier.countDown
    case GetRefState =>
      self.reply(refState.get.get)
      notifier.countDown
    case SetMapState(key, msg) =>
      mapState.put(key, msg)
      self.reply(msg)
      notifier.countDown
    case SetVectorState(msg) =>
      vectorState.add(msg)
      self.reply(msg)
      notifier.countDown
    case SetRefState(msg) =>
      refState.swap(msg)
      self.reply(msg)
      notifier.countDown
    case Success(key, msg) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      self.reply(msg)
      notifier.countDown
    case Failure(key, msg, failer) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      failer !! "Failure"
      self.reply(msg)
      notifier.countDown

    case SetMapStateOneWay(key, msg) =>
      mapState.put(key, msg)
      notifier.countDown
    case SetVectorStateOneWay(msg) =>
      vectorState.add(msg)
      notifier.countDown
    case SetRefStateOneWay(msg) =>
      refState.swap(msg)
      notifier.countDown
    case SuccessOneWay(key, msg) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      notifier.countDown
    case FailureOneWay(key, msg, failer) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      failer ! "Failure"
      notifier.countDown
  }
}

@serializable
class FailerTransactor extends Transactor {

  def receive = {
    case "Failure" =>
      throw new RuntimeException("expected")
  }
}

class TransactorSpec extends JUnitSuite {
  @Test
  def shouldOneWayMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    stateful ! SetMapStateOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert("new state" === (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def shouldMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful !! GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")).get)
  }

  @Test
  def shouldOneWayMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    val failer = actorOf[FailerTransactor]
    failer.start
    stateful ! SetMapStateOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert("init" === (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def shouldMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = actorOf[FailerTransactor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful !! GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure")).get) // check that state is == init state
  }

  @Test
  def shouldOneWayVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert(2 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetVectorState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert(2 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldOneWayVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    stateful ! SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = actorOf[FailerTransactor]
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert(1 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetVectorState("init") // set init state
    val failer = actorOf[FailerTransactor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert(1 === (stateful !! GetVectorSize).get)
  }

  @Test
  def shouldOneWayRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    stateful ! SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert("new state" === (stateful !! GetRefState).get)
  }

  @Test
  def shouldRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetRefState("init") // set init state
    stateful !! Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful !! GetRefState).get)
  }

  @Test
  def shouldOneWayRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(new StatefulTransactor(2))
    stateful.start
    stateful ! SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = actorOf[FailerTransactor]
    failer.start
    stateful ! FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful !! GetNotifier).as[CountDownLatch]
    assert(notifier.get.await(1, TimeUnit.SECONDS))
    assert("init" === (stateful !! (GetRefState, 1000000)).get) // check that state is == init state
  }

  @Test
  def shouldRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf[StatefulTransactor]
    stateful.start
    stateful !! SetRefState("init") // set init state
    val failer = actorOf[FailerTransactor]
    failer.start
    try {
      stateful !! Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful !! GetRefState).get) // check that state is == init state
  }
}
