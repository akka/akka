/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import akka.testkit.{ ImplicitSender, TestKit }
import org.scalatest.FunSuiteLike
import org.scalatest.matchers.ShouldMatchers

//#demo-code
import scala.collection._
import scala.concurrent.duration._
import scala.math.BigDecimal.int2bigDecimal

import akka.actor._
/**
 * Sample and test code for the aggregator patter.
 * This is based on Jamie Allen's tutorial at
 * http://jaxenter.com/tutorial-asynchronous-programming-with-akka-actors-46220.html
 */

sealed trait AccountType
case object Checking extends AccountType
case object Savings extends AccountType
case object MoneyMarket extends AccountType

case class GetCustomerAccountBalances(id: Long, accountTypes: Set[AccountType])
case class GetAccountBalances(id: Long)

case class AccountBalances(accountType: AccountType,
                           balance: Option[List[(Long, BigDecimal)]])

case class CheckingAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class SavingsAccountBalances(balances: Option[List[(Long, BigDecimal)]])
case class MoneyMarketAccountBalances(balances: Option[List[(Long, BigDecimal)]])

case object TimedOut
case object CantUnderstand

class SavingsAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! SavingsAccountBalances(Some(List((1, 150000), (2, 29000))))
  }
}
class CheckingAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! CheckingAccountBalances(Some(List((3, 15000))))
  }
}
class MoneyMarketAccountProxy extends Actor {
  def receive = {
    case GetAccountBalances(id: Long) ⇒
      sender ! MoneyMarketAccountBalances(None)
  }
}

class AccountBalanceRetriever extends Actor with Aggregator {

  import context._

  //#initial-expect
  expectOnce {
    case GetCustomerAccountBalances(id, types) ⇒
      new AccountAggregator(sender, id, types)
    case _ ⇒
      sender ! CantUnderstand
      context.stop(self)
  }
  //#initial-expect

  class AccountAggregator(originalSender: ActorRef,
                          id: Long, types: Set[AccountType]) {

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
    def fetchCheckingAccountsBalance() {
      context.actorOf(Props[CheckingAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case CheckingAccountBalances(balances) ⇒
          results += (Checking -> balances)
          collectBalances()
      }
    }
    //#expect-balance

    def fetchSavingsAccountsBalance() {
      context.actorOf(Props[SavingsAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case SavingsAccountBalances(balances) ⇒
          results += (Savings -> balances)
          collectBalances()
      }
    }

    def fetchMoneyMarketAccountsBalance() {
      context.actorOf(Props[MoneyMarketAccountProxy]) ! GetAccountBalances(id)
      expectOnce {
        case MoneyMarketAccountBalances(balances) ⇒
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

//#chain-sample
case class InitialRequest(name: String)
case class Request(name: String)
case class Response(name: String, value: String)
case class EvaluationResults(name: String, eval: List[Int])
case class FinalResponse(qualifiedValues: List[String])

/**
 * An actor sample demonstrating use of unexpect and chaining.
 * This is just an example and not a complete test case.
 */
class ChainingSample extends Actor with Aggregator {

  expectOnce {
    case InitialRequest(name) ⇒ new MultipleResponseHandler(sender, name)
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

    def processList() {
      unexpect(handle)

      if (values.size > 0) {
        context.actorSelection("/user/evaluator") ! values.toList
        expectOnce {
          case EvaluationResults(name, eval) ⇒ processFinal(eval)
        }
      } else processFinal(List.empty[Int])
    }
    //#unexpect-sample

    def processFinal(eval: List[Int]) {
      // Select only the entries coming back from eval
      originalSender ! FinalResponse(eval map values)
      context.stop(self)
    }
  }
}
//#chain-sample

class AggregatorSpec extends TestKit(ActorSystem("test")) with ImplicitSender with FunSuiteLike with ShouldMatchers {

  test("Test request 1 account type") {
    system.actorOf(Props[AccountBalanceRetriever]) ! GetCustomerAccountBalances(1, Set(Savings))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 1
      case result ⇒
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }

  test("Test request 3 account types") {
    system.actorOf(Props[AccountBalanceRetriever]) !
      GetCustomerAccountBalances(1, Set(Checking, Savings, MoneyMarket))
    receiveOne(10.seconds) match {
      case result: List[_] ⇒
        result should have size 3
      case result ⇒
        assert(condition = false, s"Expect List, got ${result.getClass}")
    }
  }
}
