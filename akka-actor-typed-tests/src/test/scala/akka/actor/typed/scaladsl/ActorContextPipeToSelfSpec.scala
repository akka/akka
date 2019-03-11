/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import scala.concurrent.Future
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success }

import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.Props
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object ActorContextPipeToSelfSpec {
  val config = ConfigFactory.parseString("""
      |pipe-to-self-spec-dispatcher {
      |  executor = thread-pool-executor
      |  type = PinnedDispatcher
      |}
    """.stripMargin)
}

final class ActorContextPipeToSelfSpec
    extends ScalaTestWithActorTestKit(ActorContextPipeToSelfSpec.config)
    with WordSpecLike {

  "The Scala DSL ActorContext pipeToSelf" must {
    "handle success" in { responseFrom(Future.successful("hi")) should ===("ok: hi") }
    "handle failure" in { responseFrom(Future.failed(Fail)) should ===(s"ko: $Fail") }
  }

  object Fail extends NoStackTrace

  private def responseFrom(future: Future[String]) = {
    final case class Msg(response: String, selfName: String, threadName: String)

    val probe = TestProbe[Msg]()
    val behavior = Behaviors.setup[Msg] { context =>
      context.pipeToSelf(future) {
        case Success(s) => Msg(s"ok: $s", context.self.path.name, Thread.currentThread().getName)
        case Failure(e) => Msg(s"ko: $e", context.self.path.name, Thread.currentThread().getName)
      }
      Behaviors.receiveMessage { msg =>
        probe.ref ! msg
        Behaviors.stopped
      }
    }
    val name = "pipe-to-self-spec"
    val props = Props.empty.withDispatcherFromConfig("pipe-to-self-spec-dispatcher")

    spawn(behavior, name, props)

    val msg = probe.expectMessageType[Msg]

    msg.selfName should ===("pipe-to-self-spec")
    msg.threadName should startWith("ActorContextPipeToSelfSpec-pipe-to-self-spec-dispatcher")
    msg.response
  }

}
