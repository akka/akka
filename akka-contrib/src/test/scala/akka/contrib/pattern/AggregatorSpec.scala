/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern

import akka.testkit.{ ImplicitSender, TestKit }
import org.scalatest.FunSuiteLike
import org.scalatest.Matchers
import scala.annotation.tailrec
import scala.collection._
import scala.concurrent.duration._
import scala.math.BigDecimal.int2bigDecimal
import akka.actor._
import org.scalatest.BeforeAndAfterAll
/**
 * Sample and test code for the aggregator patter.
 * This is based on Jamie Allen's tutorial at
 * http://jaxenter.com/tutorial-asynchronous-programming-with-akka-actors-46220.html
 */

sealed trait AccountType
case object Checking extends AccountType
case object Savings extends AccountType
case object MoneyMarket extends AccountType

final case class GetCustomerAccountBalances(id: Long, accountTypes: Set[AccountType])
final case class GetAccountBalances(id: Long)

final case class AccountBalances(
  accountType: AccountType,
  balance:     Option[List[(Long, BigDecimal)]])

final case class CheckingAccountBalances(balances: Option[List[(Long, BigDecimal)]])
final case class SavingsAccountBalances(balances: Option[List[(Long, BigDecimal)]])
final case class MoneyMarketAccountBalances(balances: Option[List[(Long, BigDecimal)]])

case object TimedOut
case object CantUnderstand

class SavingsAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender() ! SavingsAccountBalances(Some(List((1, 150000), (2, 29000))))
  }
}
class CheckingAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender() ! CheckingAccountBalances(Some(List((3, 15000))))
  }
}
class MoneyMarketAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender() ! MoneyMarketAccountBalances(None)
  }
}

class AccountBalanceRetriever extends Actor with Aggregator {

  import context._

  //#initial-expect
  expectOnce {
    case GetCustomerAccountBalances(id, types) ⇒
      new AccountAggregator(sender(), id, types)
    case _ ⇒
      sender() ! CantUnderstand
      context.stop(self)
  }
  //#initial-expect

  class AccountAggregator(
    originalSender: ActorRef,
    id:             Long, types: Set[AccountType]) {

    val results =
      mutable.ArrayBuffer.empty[(AccountType, Option[List[(Long, BigDecimal)]])]

    if (types.size > 0)
      types foreach {
        case Checking    ⇒ fetchCheckingAccountsBalance()
        case Savings     ⇒ fetchSavingsAccountsBalance()
        case MoneyMarket ⇒ fetchMoneyMarketAccountsBalance()
      }
    else collectBalances() // Empty type list yields empty response

    context.system.scheduler.scheduleOnce(1.second, self, TimedOut)
    //#expect-timeout
    expect {
      case TimedOut ⇒ collectBalances(force = true)
    }
    //#expect-timeout

    //#expect-balance
    def fetchCheckingAccountsBalance(): Unit = {
      context.actorOf(Props[CheckingAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case CheckingAccountBalances(balances) ⇒
          results += (Checking → balances)
          collectBalances()
      }
    }
    //#expect-balance

    def fetchSavingsAccountsBalance(): Unit = {
      context.actorOf(Props[SavingsAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case SavingsAccountBalances(balances) ⇒
          results += (Savings → balances)
          collectBalances()
      }
    }

    def fetchMoneyMarketAccountsBalance(): Unit = {
      context.actorOf(Props[MoneyMarketAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case MoneyMarketAccountBalances(balances) ⇒
          results += (MoneyMarket → balances)
          collectBalances()
      }
    }

    def collectBalances(force: Boolean = false): Unit = {
      if (results.size == types.size || force) {
        originalSender ! results.toList // Make sure it becomes immutable
        context.stop(self)
      }
    }
  }
}
//#demo-code

//#chain-sample
final case class InitialRequest(name: String)
final case class Request(name: String)
final case class Response(name: String, value: String)
final case class EvaluationResults(name: String, eval: List[Int])
final case class FinalResponse(qualifiedValues: List[String])

/**
 * An actor sample demonstrating use of unexpect and chaining.
 * This is just an example and not a complete test case.
 */
class ChainingSample extends Actor with Aggregator {

  expectOnce {
    case InitialRequest(name) ⇒ new MultipleResponseHandler(sender(), name)
  }

  class MultipleResponseHandler(originalSender: ActorRef, propName: String) {

    import context.dispatcher
    import collection.mutable.ArrayBuffer

    val values = ArrayBuffer.empty[String]

    context.actorSelection("/user/request_proxies") ! Request(propName)
    context.system.scheduler.scheduleOnce(50.milliseconds, self, TimedOut)

    //#unexpect-sample
    val handle = expect {
      case Response(name, value) ⇒
        values += value
        if (values.size > 3) processList()
      case TimedOut ⇒ processList()
    }

    def processList(): Unit = {
      unexpect(handle)

      if (values.size > 0) {
        context.actorSelection("/user/evaluator") ! values.toList
        expectOnce {
          case EvaluationResults(name, eval) ⇒ processFinal(eval)
        }
      } else processFinal(List.empty[Int])
    }
    //#unexpect-sample

    def processFinal(eval: List[Int]): Unit = {
      // Select only the entries coming back from eval
      originalSender ! FinalResponse(eval map values)
      context.stop(self)
    }
  }
}
//#chain-sample

class AggregatorSpec extends TestKit(ActorSystem("AggregatorSpec")) with ImplicitSender with FunSuiteLike with Matchers with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    shutdown()
  }

  test("Test request 1 account type") {
    system.actorOf(Props[AccountBalanceRetriever]) ! GetCustomerAccountBalances(1, Set(Savings))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 1
      case result ⇒
        assert(false, s"Expect List, got ${result.getClass}")
    }
  }

  test("Test request 3 account types") {
    system.actorOf(Props[AccountBalanceRetriever]) !
      GetCustomerAccountBalances(1, Set(Checking, Savings, MoneyMarket))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 3
      case result ⇒
        assert(false, s"Expect List, got ${result.getClass}")
    }
  }
}

final case class TestEntry(id: Int)

class WorkListSpec extends FunSuiteLike {

  val workList = WorkList.empty[TestEntry]
  var entry2: TestEntry = null
  var entry4: TestEntry = null

  test("Processing empty WorkList") {
    // ProcessAndRemove something in the middle
    val processed = workList process {
      case TestEntry(9) ⇒ true
      case _            ⇒ false
    }
    assert(!processed)
  }

  test("Insert temp entries") {
    assert(workList.head === workList.tail)

    val entry0 = TestEntry(0)
    workList.add(entry0, permanent = false)

    assert(workList.head.next != null)
    assert(workList.tail === workList.head.next)
    assert(workList.tail.ref.get === entry0)

    val entry1 = TestEntry(1)
    workList.add(entry1, permanent = false)

    assert(workList.head.next != workList.tail)
    assert(workList.head.next.ref.get === entry0)
    assert(workList.tail.ref.get === entry1)

    entry2 = TestEntry(2)
    workList.add(entry2, permanent = false)

    assert(workList.tail.ref.get === entry2)

    val entry3 = TestEntry(3)
    workList.add(entry3, permanent = false)

    assert(workList.tail.ref.get === entry3)
  }

  test("Process temp entries") {

    // ProcessAndRemove something in the middle
    assert(workList process {
      case TestEntry(2) ⇒ true
      case _            ⇒ false
    })

    // ProcessAndRemove the head
    assert(workList process {
      case TestEntry(0) ⇒ true
      case _            ⇒ false
    })

    // ProcessAndRemove the tail
    assert(workList process {
      case TestEntry(3) ⇒ true
      case _            ⇒ false
    })
  }

  test("Re-insert permanent entry") {
    entry4 = TestEntry(4)
    workList.add(entry4, permanent = true)

    assert(workList.tail.ref.get === entry4)
  }

  test("Process permanent entry") {
    assert(workList process {
      case TestEntry(4) ⇒ true
      case _            ⇒ false
    })
  }

  test("Remove permanent entry") {
    val removed = workList remove entry4
    assert(removed)
  }

  test("Remove temp entry already processed") {
    val removed = workList remove entry2
    assert(!removed)
  }

  test("Process non-matching entries") {

    val processed =
      workList process {
        case TestEntry(2) ⇒ true
        case _            ⇒ false
      }

    assert(!processed)

    val processed2 =
      workList process {
        case TestEntry(5) ⇒ true
        case _            ⇒ false
      }

    assert(!processed2)

  }

  test("Append two lists") {
    workList.removeAll()
    0 to 4 foreach { id ⇒ workList.add(TestEntry(id), permanent = false) }

    val l2 = new WorkList[TestEntry]
    5 to 9 foreach { id ⇒ l2.add(TestEntry(id), permanent = true) }

    workList addAll l2

    @tailrec
    def checkEntries(id: Int, entry: WorkList.Entry[TestEntry]): Int = {
      if (entry == null) id
      else {
        assert(entry.ref.get.id === id)
        checkEntries(id + 1, entry.next)
      }
    }

    assert(checkEntries(0, workList.head.next) === 10)
  }

  test("Clear list") {
    workList.removeAll()
    assert(workList.head.next === null)
    assert(workList.tail === workList.head)
  }

  val workList2 = WorkList.empty[PartialFunction[Any, Unit]]

  val fn1: PartialFunction[Any, Unit] = {
    case s: String ⇒
      val result1 = workList2 remove fn1
      assert(result1 === true, "First remove must return true")
      val result2 = workList2 remove fn1
      assert(result2 === false, "Second remove must return false")
  }

  val fn2: PartialFunction[Any, Unit] = {
    case s: String ⇒
      workList2.add(fn1, permanent = true)
  }

  test("Reentrant insert") {
    workList2.add(fn2, permanent = false)
    assert(workList2.head.next != null)
    assert(workList2.tail == workList2.head.next)

    // Processing inserted fn1, reentrant adding fn2
    workList2 process { fn ⇒
      var processed = true
      fn.applyOrElse("Foo", (_: Any) ⇒ processed = false)
      processed
    }
  }

  test("Reentrant delete") {
    // Processing inserted fn2, should delete itself
    workList2 process { fn ⇒
      var processed = true
      fn.applyOrElse("Foo", (_: Any) ⇒ processed = false)
      processed
    }
  }
}
