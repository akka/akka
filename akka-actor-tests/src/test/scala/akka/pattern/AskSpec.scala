/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.postfixOps

import akka.testkit.AkkaSpec
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.testkit.DefaultTimeout
import akka.util.Timeout
import scala.util.Failure
import akka.actor.{ Actor, Props, ActorRef }

class AskSpec extends AkkaSpec {

  "The “ask” pattern" must {

    "return broken promises on DeadLetters" in {
      implicit val timeout = Timeout(5 seconds)
      val dead = system.actorFor("/system/deadLetters")
      val f = dead.ask(42)(1 second)
      f.isCompleted must be(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on EmptyLocalActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val empty = system.actorFor("unknown")
      val f = empty ? 3.14
      f.isCompleted must be(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on unsupported ActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val f = ask(null: ActorRef, 3.14)
      f.isCompleted must be(true)
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      }.getMessage must be === "Unsupported type of ActorRef for the recipient. Question not sent to [null]"
    }

    "return broken promises on 0 timeout" in {
      implicit val timeout = Timeout(0 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length for an `ask` must be greater or equal to 1.  Question not sent to [%s]" format echo
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      }.getMessage must be === expectedMsg
    }

    "return broken promises on < 0 timeout" in {
      implicit val timeout = Timeout(-1000 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length for an `ask` must be greater or equal to 1.  Question not sent to [%s]" format echo
      intercept[IllegalArgumentException] {
        Await.result(f, remaining)
      }.getMessage must be === expectedMsg
    }

  }

}