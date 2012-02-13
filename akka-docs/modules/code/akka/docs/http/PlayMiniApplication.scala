/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.http

//#imports
import com.typesafe.play.mini.{ POST, GET, Path, Application }
import play.api.mvc.{ Action, AsyncResult }
import play.api.mvc.Results._
import play.api.libs.concurrent._
import play.api.data._
import play.api.data.Forms._
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import akka.actor.{ ActorSystem, Props, Actor }
import scala.collection.mutable.{ Map ⇒ MutableMap }
//#imports

//#playMiniDefinition
object PlayMiniApplication extends Application {
  //#playMiniDefinition
  private val system = ActorSystem("sample")
  //#regexURI
  private final val StatementPattern = """/account/statement/(\w+)""".r
  //#regexURI
  private lazy val accountActor = system.actorOf(Props[AccountActor])
  implicit val timeout = Timeout(1000 milliseconds)

  //#route
  def route = {
    //#routeLogic
    //#simpleGET
    case GET(Path("/ping")) ⇒ Action {
      Ok("Pong @ " + System.currentTimeMillis)
    }
    //#simpleGET
    //#regexGET
    case GET(Path(StatementPattern(accountId))) ⇒ Action {
      AsyncResult {
        //#innerRegexGET
        (accountActor ask Status(accountId)).mapTo[Int].asPromise.map { r ⇒
          if (r >= 0) Ok("Account total: " + r)
          else BadRequest("Unknown account: " + accountId)
        }
        //#innerRegexGET
      }
    }
    //#regexGET
    //#asyncDepositPOST
    case POST(Path("/account/deposit")) ⇒ Action { implicit request ⇒
      //#formAsyncDepositPOST
      val (accountId, amount) = commonForm.bindFromRequest.get
      //#formAsyncDepositPOST
      AsyncResult {
        (accountActor ask Deposit(accountId, amount)).mapTo[Int].asPromise.map { r ⇒ Ok("Updated account total: " + r) }
      }
    }
    //#asyncDepositPOST
    //#asyncWithdrawPOST
    case POST(Path("/account/withdraw")) ⇒ Action { implicit request ⇒
      val (accountId, amount) = commonForm.bindFromRequest.get
      AsyncResult {
        (accountActor ask Withdraw(accountId, amount)).mapTo[Int].asPromise.map { r ⇒
          if (r >= 0) Ok("Updated account total: " + r)
          else BadRequest("Unknown account or insufficient funds. Get your act together.")
        }
      }
    }
    //#asyncWithdrawPOST
    //#routeLogic
  }
  //#route

  //#form
  val commonForm = Form(
    tuple(
      "accountId" -> nonEmptyText,
      "amount" -> number(min = 1)))
  //#form
}

//#cases
case class Status(accountId: String)
case class Deposit(accountId: String, amount: Int)
case class Withdraw(accountId: String, amount: Int)
//#cases

//#actor
class AccountActor extends Actor {
  var accounts = MutableMap[String, Int]()

  //#receive
  def receive = {
    //#senderBang
    case Status(accountId)           ⇒ sender ! accounts.getOrElse(accountId, -1)
    //#senderBang
    case Deposit(accountId, amount)  ⇒ sender ! deposit(accountId, amount)
    case Withdraw(accountId, amount) ⇒ sender ! withdraw(accountId, amount)
  }
  //#receive

  private def deposit(accountId: String, amount: Int): Int = {
    accounts.get(accountId) match {
      case Some(value) ⇒
        val newValue = value + amount
        accounts += accountId -> newValue
        newValue
      case None ⇒
        accounts += accountId -> amount
        amount
    }
  }

  private def withdraw(accountId: String, amount: Int): Int = {
    accounts.get(accountId) match {
      case Some(value) ⇒
        if (value < amount) -1
        else {
          val newValue = value - amount
          accounts += accountId -> newValue
          newValue
        }
      case None ⇒ -1
    }
  }
  //#actor
}