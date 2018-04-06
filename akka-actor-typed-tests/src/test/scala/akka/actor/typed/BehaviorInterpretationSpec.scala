/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.{ InvalidMessageException, typed }
import akka.testkit.typed.internal.StubbedActorContext
import org.scalatest.{ Matchers, WordSpec }

// low level spec to make sure we notice if we cause regressions
class BehaviorInterpretationSpec extends WordSpec with Matchers {

  val ctx = new StubbedActorContext[Any]("whatever")

  "Behavior.interpretMessage" should {

    "throw exception on null message" in {
      intercept[InvalidMessageException] {
        Behavior.interpretMessage(Behavior.empty, ctx, null)
      }
    }

    "return unhandled for empty behavior" in {
      Behavior.interpretMessage(Behavior.empty, ctx, "message") should ===(Behavior.unhandled)
    }

    "return same for ignored message" in {
      Behavior.interpretMessage(Behavior.ignore, ctx, "message") should ===(Behavior.same)
    }

    "return the same stopped behavior" in {
      val stopped = Behavior.stopped[Any]
      Behavior.interpretMessage(stopped, ctx, "message") should be theSameInstanceAs (stopped)
    }

    "throw for same" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretMessage(Behavior.same, ctx, "message")
      }
    }

    "throw for unhandled" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretMessage(Behavior.unhandled, ctx, "message")
      }
    }

    "throw for deferred" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretMessage(Behavior.DeferredBehavior[Any](ctx ⇒ Behavior.stopped), ctx, "message")
      }
    }

    "invoke extensible receive" in {
      var seenMsg: Any = null
      Behavior.interpretMessage(new ExtensibleBehavior[Any] {
        def receive(ctx: ActorContext[Any], msg: Any): Behavior[Any] = {
          seenMsg = msg
          Behavior.same
        }

        def receiveSignal(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ???
      }, ctx, "message") should ===(Behavior.same)
      seenMsg should ===("message")
    }

    "start return of extensible receive" in {
      var started = false
      Behavior.interpretMessage(new ExtensibleBehavior[Any] {
        def receive(ctx: ActorContext[Any], msg: Any): Behavior[Any] = {

          Behavior.DeferredBehavior[Any] { ctx ⇒
            started = true
            Behavior.empty
          }
        }

        def receiveSignal(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = ???
      }, ctx, "message") should ===(Behavior.empty)

      started should ===(true)
    }

  }

  "Behavior.interpretSignal" should {

    "throw exception on null signal" in {
      intercept[InvalidMessageException] {
        Behavior.interpretSignal(Behavior.empty, ctx, null)
      }
    }

    "return unhandled for empty behavior" in {
      Behavior.interpretSignal(Behavior.empty, ctx, PreRestart) should ===(Behavior.unhandled)
    }

    "return same for ignored signal" in {
      Behavior.interpretSignal(Behavior.ignore, ctx, PreRestart) should ===(Behavior.same)
    }

    "return the same stopped behavior" in {
      val stopped = Behavior.stopped[Any]
      Behavior.interpretSignal(stopped, ctx, PreRestart) should be theSameInstanceAs (stopped)
    }

    "throw for same" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretSignal(Behavior.same, ctx, PreRestart)
      }
    }

    "throw for unhandled" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretSignal(Behavior.unhandled, ctx, PreRestart)
      }
    }

    "throw for deferred" in {
      intercept[IllegalArgumentException] {
        Behavior.interpretSignal(Behavior.DeferredBehavior[Any](ctx ⇒ Behavior.stopped), ctx, PreRestart)
      }
    }

    "invoke extensible receive" in {
      var seenSignal: Any = null
      Behavior.interpretSignal(new ExtensibleBehavior[Any] {
        def receive(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ???
        def receiveSignal(ctx: ActorContext[Any], signal: Signal): Behavior[Any] = {
          seenSignal = signal
          Behavior.same
        }
      }, ctx, PreRestart) should ===(Behavior.same)

      seenSignal should ===(PreRestart)
    }

    "start return of extensible receive" in {
      var started = false
      Behavior.interpretSignal(new ExtensibleBehavior[Any] {
        def receive(ctx: ActorContext[Any], msg: Any): Behavior[Any] = ???
        def receiveSignal(ctx: ActorContext[Any], msg: Signal): Behavior[Any] = {
          Behavior.DeferredBehavior[Any] { ctx ⇒
            started = true
            Behavior.empty
          }
        }
      }, ctx, PreRestart) should ===(Behavior.empty)

      started should ===(true)
    }

  }

}
