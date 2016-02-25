/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.pattern

import akka.actor._
import akka.testkit.{ TestProbe, AkkaSpec }
import akka.util.Timeout
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

import language.postfixOps

class AskSpec extends AkkaSpec {

  "The “ask” pattern" must {
    "send request to actor and wrap the answer in Future" in {
      implicit val timeout = Timeout(5.seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }))
      val f = echo ? "ping"
      f.futureValue should ===("ping")
    }

    "return broken promises on DeadLetters" in {
      implicit val timeout = Timeout(5 seconds)
      val dead = system.actorFor("/system/deadLetters")
      val f = dead.ask(42)(1 second)
      f.isCompleted should ===(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on EmptyLocalActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val empty = system.actorFor("unknown")
      val f = empty ? 3.14
      f.isCompleted should ===(true)
      f.value.get match {
        case Failure(_: AskTimeoutException) ⇒
        case v                               ⇒ fail(v + " was not Left(AskTimeoutException)")
      }
    }

    "return broken promises on unsupported ActorRefs" in {
      implicit val timeout = Timeout(5 seconds)
      val f = ask(null: ActorRef, 3.14)
      f.isCompleted should ===(true)
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should ===("Unsupported recipient ActorRef type, question not sent to [null]. Sender[null] sent the message of type \"java.lang.Double\".")
    }

    "return broken promises on 0 timeout" in {
      implicit val timeout = Timeout(0 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]. Sender[null] sent the message of type \"java.lang.String\"." format echo
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should ===(expectedMsg)
    }

    "return broken promises on < 0 timeout" in {
      implicit val timeout = Timeout(-1000 seconds)
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }))
      val f = echo ? "foo"
      val expectedMsg = "Timeout length must not be negative, question not sent to [%s]. Sender[null] sent the message of type \"java.lang.String\"." format echo
      intercept[IllegalArgumentException] {
        Await.result(f, timeout.duration)
      }.getMessage should ===(expectedMsg)
    }

    "include target information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      val silentOne = system.actorOf(Props.empty, "silent")
      val f = silentOne ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage.contains("/user/silent") should ===(true)
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
      }.getMessage.contains(sender.toString) should ===(true)
    }

    "include message class information in AskTimeout" in {
      implicit val timeout = Timeout(0.5 seconds)
      val f = system.actorOf(Props.empty) ? "noreply"
      intercept[AskTimeoutException] {
        Await.result(f, 1 second)
      }.getMessage.contains("\"java.lang.String\"") should ===(true)
    }

    "work for ActorSelection" in {
      implicit val timeout = Timeout(5 seconds)
      import system.dispatcher
      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ sender() ! x } }), "select-echo")
      val identityFuture = (system.actorSelection("/user/select-echo") ? Identify(None))
        .mapTo[ActorIdentity].map(_.ref.get)

      Await.result(identityFuture, 5 seconds) should ===(echo)
    }

    "work when reply uses actor selection" in {
      implicit val timeout = Timeout(5 seconds)
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ context.actorSelection(sender().path) ! x } }), "select-echo2")
      val f = echo ? "hi"

      Await.result(f, 1 seconds) should ===("hi")

      deadListener.expectNoMsg(200 milliseconds)
    }

    "throw AskTimeoutException on using *" in {
      implicit val timeout = Timeout(0.5 seconds)
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val echo = system.actorOf(Props(new Actor { def receive = { case x ⇒ context.actorSelection("/temp/*") ! x } }), "select-echo3")
      val f = echo ? "hi"
      intercept[AskTimeoutException] {
        Await.result(f, 1 seconds)
      }

      deadListener.expectMsgClass(200 milliseconds, classOf[DeadLetter])
    }

    "throw AskTimeoutException on using .." in {
      implicit val timeout = Timeout(0.5 seconds)
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val echo = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒
            val name = sender.path.name
            val parent = sender.path.parent
            context.actorSelection(parent / ".." / "temp" / name) ! x
        }
      }), "select-echo4")
      val f = echo ? "hi"
      intercept[AskTimeoutException] {
        Await.result(f, 1 seconds) should ===("hi")
      }

      deadListener.expectMsgClass(200 milliseconds, classOf[DeadLetter])
    }

    "send to DeadLetter when child does not exist" in {
      implicit val timeout = Timeout(0.5 seconds)
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val echo = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒
            val name = sender.path.name
            val parent = sender.path.parent
            context.actorSelection(parent / "missing") ! x
        }
      }), "select-echo5")
      val f = echo ? "hi"
      intercept[AskTimeoutException] {
        Await.result(f, 1 seconds) should ===("hi")
      }
      deadListener.expectMsgClass(200 milliseconds, classOf[DeadLetter])
    }

    "send DeadLetter when answering to grandchild" in {
      implicit val timeout = Timeout(0.5 seconds)
      val deadListener = TestProbe()
      system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

      val echo = system.actorOf(Props(new Actor {
        def receive = {
          case x ⇒
            val name = sender.path.name
            val parent = sender.path.parent
            context.actorSelection(sender().path / "missing") ! x
        }
      }), "select-echo6")
      val f = echo ? "hi"
      intercept[AskTimeoutException] {
        Await.result(f, 1 seconds) should ===(ActorSelectionMessage("hi", Vector(SelectChildName("missing")), false))
      }
      deadListener.expectMsgClass(200 milliseconds, classOf[DeadLetter])
    }

    "allow watching the promiseActor and send Terminated() when completes" in {
      implicit val timeout = Timeout(300 millis)
      val p = TestProbe()

      val act = system.actorOf(Props(new Actor {
        def receive = {
          case msg ⇒ p.ref ! sender() -> msg
        }
      }))

      val f = (act ? "ask").mapTo[String]
      val (promiseActorRef, "ask") = p.expectMsgType[(ActorRef, String)]

      watch(promiseActorRef)
      promiseActorRef ! "complete"

      val completed = f.futureValue
      completed should ===("complete")
      expectTerminated(promiseActorRef, 1.second)
    }
  }

}
