/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import language.postfixOps

import akka.actor._
import akka.testkit.AkkaSpec
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class AskSpec extends AkkaSpec {

  "The “ask” pattern" must {

    "return broken promises on DeadLetters" in {
      implicit val timeout = Timeout(5 seconds)
      val dead = system.actorFor("/system/deadLetters")
      val f = dead.ask(42)(1 second)
      f.isCompleted should be(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on EmptyLocalActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val empty = system.actorFor("unknown")
      val f = empty ? 3.14
      f.isCompleted should be(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on unsupported ActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val f = ask(null: ActorRef, 3.14)
      f.isCompleted should be(true)
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should be("Unsupported recipient ActorRef type, question not sent to [null]. Sender[null] sent the message of type \"java.lang.Double\".")
    }

    "return broken promises on 0 timeout" in {
      implicit val timeout = Timeout(0 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]. Sender[null] sent the message of type \"java.lang.String\"." format echo
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should be(expectedMsg)
    }

    "return broken promises on < 0 timeout" in {
      implicit val timeout = Timeout(-1000 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]. Sender[null] sent the message of type \"java.lang.String\"." format echo
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should be(expectedMsg)
    }

    "include target information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      val silentOne = system.actorOf(Props.empty, "silent")
      val f = silentOne ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage.contains("/user/silent") should be(true)
    }

    "include timeout information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      val f = system.actorOf(Props.empty) ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage should include(timeout.duration.toMillis.toString)
    }

    "include sender information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      implicit val sender = system.actorOf(Props.empty)
      val f = system.actorOf(Props.empty) ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage.contains(sender.toString) should be(true)
    }

    "include message class information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      val f = system.actorOf(Props.empty) ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage.contains("\"java.lang.String\"") should be(true)
    }

    "work for ActorSelection" in {
      implicit val timeout = Timeout(5 seconds)
      import system.dispatcher
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }), "select-echo")
      val identityFuture = (system.actorSelection("/user/select-echo") ? Identify(None))
        .mapTo[ActorIdentity].map(_.ref.get)

      Await.result(identityFuture, 5 seconds) should be(echo)
    }

  }

}
