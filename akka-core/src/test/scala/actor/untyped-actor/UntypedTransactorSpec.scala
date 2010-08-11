package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.stm.{Ref, TransactionalMap, TransactionalVector}
import UntypedActor._

object UntypedTransactorSpec {
  case class GetMapState(key: String)
  case object GetVectorState
  case object GetVectorSize
  case object GetRefState

  case class SetMapState(key: String, value: String)
  case class SetVectorState(key: String)
  case class SetRefState(key: String)
  case class Success(key: String, value: String)
  case class Failure(key: String, value: String, failer: UntypedActorRef)

  case class SetMapStateOneWay(key: String, value: String)
  case class SetVectorStateOneWay(key: String)
  case class SetRefStateOneWay(key: String)
  case class SuccessOneWay(key: String, value: String)
  case class FailureOneWay(key: String, value: String, failer: UntypedActorRef)

  case object GetNotifier
}
import UntypedTransactorSpec._

class StatefulUntypedTransactor(expectedInvocationCount: Int) extends UntypedTransactor {
  def this() = this(0)
  getContext.setTimeout(5000)

  val notifier = new CountDownLatch(expectedInvocationCount)

  private val mapState = TransactionalMap[String, String]()
  private val vectorState = TransactionalVector[String]()
  private val refState = Ref[String]()

  def onReceive(message: Any) = message match {
    case GetNotifier =>
      getContext.replyUnsafe(notifier)
    case GetMapState(key) =>
      getContext.replyUnsafe(mapState.get(key).get)
      notifier.countDown
    case GetVectorSize =>
      getContext.replyUnsafe(vectorState.length.asInstanceOf[AnyRef])
      notifier.countDown
    case GetRefState =>
      getContext.replyUnsafe(refState.get)
      notifier.countDown
    case SetMapState(key, msg) =>
      mapState.put(key, msg)
      getContext.replyUnsafe(msg)
      notifier.countDown
    case SetVectorState(msg) =>
      vectorState.add(msg)
      getContext.replyUnsafe(msg)
      notifier.countDown
    case SetRefState(msg) =>
      refState.swap(msg)
      getContext.replyUnsafe(msg)
      notifier.countDown
    case Success(key, msg) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      getContext.replyUnsafe(msg)
      notifier.countDown
    case Failure(key, msg, failer) =>
      mapState.put(key, msg)
      vectorState.add(msg)
      refState.swap(msg)
      failer.sendRequestReply("Failure")
      getContext.replyUnsafe(msg)
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
      notifier.countDown
      failer.sendOneWay("Failure",getContext)
  }
}

class StatefulUntypedTransactorExpectingTwoInvocations extends StatefulUntypedTransactor(2)

@serializable
class FailerUntypedTransactor extends UntypedTransactor {

  def onReceive(message: Any) = message match {
    case "Failure" =>
      throw new RuntimeException("Expected exception; to test fault-tolerance")
  }
}

class UntypedTransactorSpec extends JUnitSuite {

  @Test
  def shouldOneWayMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    stateful sendOneWay SetMapStateOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful sendOneWay SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(1, TimeUnit.SECONDS))
    assert("new state" === (stateful sendRequestReply GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")))
  }

  @Test
  def shouldMapShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "init") // set init state
    stateful sendRequestReply Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful sendRequestReply GetMapState("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess")))
  }

  @Test
  def shouldOneWayMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    stateful sendOneWay SetMapStateOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    stateful sendOneWay FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(5, TimeUnit.SECONDS))
    assert("init" === (stateful sendRequestReply GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure"))) // check that state is == init state
  }

  @Test
  def shouldMapShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure", "init") // set init state
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    try {
      stateful sendRequestReply Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful sendRequestReply GetMapState("testShouldRollbackStateForStatefulServerInCaseOfFailure"))) // check that state is == init state
  }

  @Test
  def shouldOneWayVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    stateful sendOneWay SetVectorStateOneWay("init") // set init state
    stateful sendOneWay SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(1, TimeUnit.SECONDS))
    assert(2 === (stateful sendRequestReply GetVectorSize))
  }

  @Test
  def shouldVectorShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetVectorState("init") // set init state
    stateful sendRequestReply Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert(2 === (stateful sendRequestReply GetVectorSize))
  }

  @Test
  def shouldOneWayVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    stateful sendOneWay SetVectorStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    stateful sendOneWay FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(1, TimeUnit.SECONDS))
    assert(1 === (stateful sendRequestReply GetVectorSize))
  }

  @Test
  def shouldVectorShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetVectorState("init") // set init state
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    try {
      stateful sendRequestReply Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert(1 === (stateful sendRequestReply GetVectorSize))
  }

  @Test
  def shouldOneWayRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    stateful sendOneWay SetRefStateOneWay("init") // set init state
    stateful sendOneWay SuccessOneWay("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(1, TimeUnit.SECONDS))
    assert("new state" === (stateful sendRequestReply GetRefState))
  }

  @Test
  def shouldRefShouldNotRollbackStateForStatefulServerInCaseOfSuccess = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetRefState("init") // set init state
    stateful sendRequestReply Success("testShouldNotRollbackStateForStatefulServerInCaseOfSuccess", "new state") // transactionrequired
    assert("new state" === (stateful sendRequestReply GetRefState))
  }

  @Test
  def shouldOneWayRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactorExpectingTwoInvocations]).start
    stateful sendOneWay SetRefStateOneWay("init") // set init state
    Thread.sleep(1000)
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    stateful sendOneWay FailureOneWay("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
    val notifier = (stateful sendRequestReply GetNotifier).asInstanceOf[CountDownLatch]
    assert(notifier.await(1, TimeUnit.SECONDS))
    assert("init" === (stateful sendRequestReply (GetRefState, 1000000))) // check that state is == init state
  }

  @Test
  def shouldRefShouldRollbackStateForStatefulServerInCaseOfFailure = {
    val stateful = actorOf(classOf[StatefulUntypedTransactor]).start
    stateful sendRequestReply SetRefState("init") // set init state
    val failer = actorOf(classOf[FailerUntypedTransactor]).start
    try {
      stateful sendRequestReply Failure("testShouldRollbackStateForStatefulServerInCaseOfFailure", "new state", failer) // call failing transactionrequired method
      fail("should have thrown an exception")
    } catch {case e: RuntimeException => {}}
    assert("init" === (stateful sendRequestReply GetRefState)) // check that state is == init state
  }
}