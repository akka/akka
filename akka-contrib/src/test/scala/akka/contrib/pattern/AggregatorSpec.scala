/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2013 Akara Sucharitakul
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package akka.contrib.pattern

import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.FunSuite
import org.scalatest.matchers.ShouldMatchers

//#demo-code
import scala.collection._
import scala.concurrent.duration._
import scala.math.BigDecimal.int2bigDecimal

import akka.actor._
/**
 * Sample and test code for the aggregator patter. This is based on Jamie Allen's tutorial at
 * http://jaxenter.com/tutorial-asynchronous-programming-with-akka-actors-46220.html
 */

sealed trait AccountType
case object Checking extends AccountType
case object Savings extends AccountType
case object MoneyMarket extends AccountType

case class GetCustomerAccountBalances(id: Long, accountTypes: Set[AccountType])
case class GetAccountBalances(id: Long)

case class AccountBalances(accountType: AccountType, balance: Option[List[(Long, BigDecimal)]])

case class CheckingAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class SavingsAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class MoneyMarketAccountBalances(balances: Option[List[(Long, BigDecimal)]])

case object TimedOut
case object CantUnderstand

class SavingsAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) =>
      sender ! SavingsAccountBalances(Some(List((1, 150000), (2, 29000))))
  }
}
class CheckingAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) =>
      sender ! CheckingAccountBalances(Some(List((3, 15000))))
  }
}
class MoneyMarketAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) =>
      sender ! MoneyMarketAccountBalances(None)
  }
}

class AccountBalanceRetriever extends Actor with Aggregator {

  import context._

  expectOnce {
    case GetCustomerAccountBalances(id, types) =>
      new AccountAggregator(sender, id, types)
    case _ =>
      sender ! CantUnderstand
      context.stop(self)
  }

  class AccountAggregator(originalSender: ActorRef, id: Long, types: Set[AccountType]) {

    val results = mutable.ArrayBuffer.empty[(AccountType, Option[List[(Long, BigDecimal)]])]

    if (types.size > 0)
      types foreach {
        case Checking => fetchCheckingAccountsBalance()
        case Savings => fetchSavingsAccountsBalance()
        case MoneyMarket => fetchMoneyMarketAccountsBalance()
      }
    else collectBalances() // Empty type list yields empty response

    context.system.scheduler.scheduleOnce(250 milliseconds) {
      self ! TimedOut
    }

    expect {
      case TimedOut => collectBalances(force = true)
    }

    def fetchCheckingAccountsBalance() {
      context.actorOf(Props[CheckingAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case CheckingAccountBalances(balances) =>
          results += (Checking -> balances)
          collectBalances()
      }
    }

    def fetchSavingsAccountsBalance() {
      context.actorOf(Props[SavingsAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case SavingsAccountBalances(balances) =>
          results += (Savings -> balances)
          collectBalances()
      }
    }

    def fetchMoneyMarketAccountsBalance() {
      context.actorOf(Props[MoneyMarketAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case MoneyMarketAccountBalances(balances) =>
          results += (MoneyMarket -> balances)
          collectBalances()
      }
    }

    def collectBalances(force: Boolean = false) {
      if (results.size == types.size || force) {
        originalSender ! results.toList // Make sure it becomes immutable
        context.stop(self)
      }
    }
  }
}
//#demo-code

class AggregatorSpec extends TestKit(ActorSystem("test")) with ImplicitSender with FunSuite with ShouldMatchers {

  test ("Test request 1 account type") {
    system.actorOf(Props[AccountBalanceRetriever]) ! GetCustomerAccountBalances(1, Set(Savings))
    receiveOne(10 seconds) match {
      case result: List[_] =>
        result should have size 1
      case result =>
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }

  test ("Test request 3 account types") {
    system.actorOf(Props[AccountBalanceRetriever]) !
      GetCustomerAccountBalances(1, Set(Checking, Savings, MoneyMarket))
    receiveOne(10 seconds) match {
      case result: List[_] =>
        result should have size 3
      case result =>
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }
}

case class TestEntry(id: Int)

class WorkListSpec extends FunSuite {

  val workList = WorkList.empty[TestEntry]
  var entry2: TestEntry = null
  var entry4: TestEntry = null

  test ("Processing empty WorkList") {
    // ProcessAndRemove something in the middle
    val processed = workList process {
      case TestEntry(9) => true
      case _ => false
    }
    assert(!processed)
  }

  test ("Insert temp entries") {
    assert(workList.head === null)
    assert(workList.tail === null)

    val entry0 = TestEntry(0)
    workList += entry0

    assert(workList.head != null)
    assert(workList.tail === workList.head)
    assert(workList.head.ref === entry0)

    val entry1 = TestEntry(1)
    workList += entry1

    assert(workList.head != workList.tail)
    assert(workList.head.ref === entry0)
    assert(workList.tail.ref === entry1)

    entry2 = TestEntry(2)
    workList += entry2

    assert(workList.tail.ref === entry2)

    val entry3 = TestEntry(3)
    workList += entry3

    assert(workList.tail.ref === entry3)
  }

  test ("Process temp entries") {

    // ProcessAndRemove something in the middle
    assert (workList process {
      case TestEntry(2) => true
      case _ => false
    })

    // ProcessAndRemove the head
    assert (workList process {
      case TestEntry(0) => true
      case _ => false
    })

    // ProcessAndRemove the tail
    assert (workList process {
      case TestEntry(3) => true
      case _ => false
    })
  }

  test ("Re-insert permanent entry") {
    implicit val permanent = true
    entry4 = TestEntry(4)
    workList += entry4

    assert(workList.tail.ref === entry4)
  }

  test ("Process permanent entry") {
    assert (workList process {
      case TestEntry(4) => true
      case _ => false
    })
  }

  test ("Remove permanent entry") {
    val removed = workList -= entry4
    assert(removed)
  }

  test ("Remove temp entry already processed") {
    val removed = workList -= entry2
    assert(!removed)
  }

  test ("Process non-matching entries") {

    val processed =
      workList process {
        case TestEntry(2) => true
        case _ => false
      }

    assert(!processed)

    val processed2 =
      workList process {
        case TestEntry(5) => true
        case _ => false
      }

    assert(!processed2)

  }

  val workList2 = WorkList.empty[PartialFunction[Any, Unit]]

  val fn1: PartialFunction[Any, Unit] = {
    case s: String =>
      implicit val permanent = true
      workList2 += fn2
  }

  val fn2: PartialFunction[Any, Unit] = {
    case s: String =>
      val result1 = workList2 -= fn2
      assert(result1 === true, "First remove must return true")
      val result2 = workList2 -= fn2
      assert(result2 === false, "Second remove must return false")
  }

  test ("Reentrant insert") {
    workList2 += fn1
    assert(workList2.head != null)
    assert(workList2.tail == workList2.head)

    // Processing inserted fn1, reentrant adding fn2
    workList2 process { fn =>
      var processed = true
      fn.applyOrElse("Foo", (_: Any) => processed = false)
      processed
    }
  }

  test ("Reentrant delete") {
    // Processing inserted fn2, should delete itself
    workList2 process { fn =>
      var processed = true
      fn.applyOrElse("Foo", (_: Any) => processed = false)
      processed
    }
  }
}
