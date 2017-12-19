package docs.akka.typed.coexistence

import akka.actor.typed._
import akka.actor.typed.scaladsl.Actor
//#adapter-import
// adds support for typed actors to an untyped actor system and context
import akka.actor.typed.scaladsl.adapter._
//#adapter-import
import akka.testkit.TestProbe
//#import-alias
import akka.{ actor ⇒ untyped }
//#import-alias
import org.scalatest.WordSpec

import scala.concurrent.duration._

object UntypedWatchingTypedSpec {
  object Untyped {
    def props() = untyped.Props(new Untyped)
  }

  //#untyped-watch
  class Untyped extends untyped.Actor {
    // context.spawn is an implicit extension method
    val second: ActorRef[Typed.Command] =
      context.spawn(Typed.behavior, "second")

    // context.watch is an implicit extension method
    context.watch(second)

    // self can be used as the `replyTo` parameter here because
    // there is an implicit conversion from akka.actor.ActorRef to
    // akka.actor.typed.ActorRef
    second ! Typed.Ping(self)

    override def receive = {
      case Typed.Pong ⇒
        println(s"$self got Pong from ${sender()}")
        // context.stop is an implicit extension method
        context.stop(second)
      case untyped.Terminated(ref) ⇒
        println(s"$self observed termination of $ref")
        context.stop(self)
    }
  }
  //#untyped-watch

  //#typed
  object Typed {
    sealed trait Command
    final case class Ping(replyTo: ActorRef[Pong.type]) extends Command
    case object Pong

    val behavior: Behavior[Command] =
      Actor.immutable { (ctx, msg) ⇒
        msg match {
          case Ping(replyTo) ⇒
            println(s"${ctx.self} got Ping from $replyTo")
            // replyTo is an untyped actor that has been converted for coexistence
            replyTo ! Pong
            Actor.same
        }
      }
  }
  //#typed
}

class UntypedWatchingTypedSpec extends WordSpec {

  import UntypedWatchingTypedSpec._

  "Untyped -> Typed" must {
    "support creating, watching and messaging" in {
      //#create-untyped
      val system = akka.actor.ActorSystem("Coexistence")
      val untyped = system.actorOf(Untyped.props())
      //#create-untyped
      val probe = TestProbe()(system)
      probe.watch(untyped)
      probe.expectTerminated(untyped, 200.millis)
      system.terminate()
    }
  }

}
