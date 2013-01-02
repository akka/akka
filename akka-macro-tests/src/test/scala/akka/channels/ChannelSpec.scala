/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ActorRef
import akka.makkros.Test._
import scala.tools.reflect.ToolBoxError

object ChannelSpec {

  trait Msg

  trait A extends Msg
  object A extends A
  object A1 extends A
  object A2 extends A

  trait B extends Msg
  object B extends B

  trait C extends Msg
  object C extends C

  trait D extends Msg
  object D extends D

  // used for sender verification in the first two test cases
  class Tester extends Channels[TNil, (A, C) :=: (B, D) :=: TNil] {
    channel[A.type] {
      case (A, s) ⇒ s ! C
    }
    channel[B] {
      case (B, s) ⇒ s ! D
    }
  }
  class RecvC(ref: ActorRef) extends Channels[TNil, (C, Nothing) :=: TNil] {
    channel[C] { case (x, _) ⇒ ref ! x }
  }

  // pos compile test for multiple reply channels
  class SubChannels extends Channels[TNil, (A, B) :=: (A, C) :=: TNil] {
    channel[A] {
      case (A1, x) ⇒
        x ! B
        x ! C
    }
  }

  // pos compile test for children
  class Children extends Channels[TNil, (A, B) :=: (C, D) :=: TNil] {
    val c = createChild(new Channels[(A, Nothing) :=: TNil, (B, C) :=: TNil] {
      channel[B] { case (B, s) ⇒ s ! C }
    })

    var client: ActorRef = _
    channel[A] {
      case (A, s) ⇒ c ! B; client = sender
    }
    channel[C] {
      case (C, _) ⇒ client ! C
    }

    createChild(new Channels[(C, Nothing) :=: TNil, TNil])
    createChild(new Channels[(A, Nothing) :=:(C, Nothing) :=: TNil, TNil])
  }
}

class ChannelSpec extends AkkaSpec with ImplicitSender {

  import ChannelSpec._

  "Channels" must {

    "construct refs" in {
      val ref = ChannelExt(system).actorOf(new Tester)
      ref ! A
      expectMsg(C)
      lastSender must be(ref.actorRef)
      ref ! B
      expectMsg(D)
      lastSender must be(ref.actorRef)
    }

    "select return channels" in {
      val ref = ChannelExt(system).actorOf(new Tester)
      implicit val sender = ChannelExt(system).actorOf(new RecvC(testActor))
      ref ! A
      expectMsg(C)
      lastSender must be(sender.actorRef)
    }

    "not permit wrong message type" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :=: TNil](null) ! B
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.B.type")
    }

    "not permit wrong message type in complex channel" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :=: (B, D) :=: TNil](null) ! C
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.C.type")
    }

    "not permit unfit sender ref" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(C, D) :=: TNil](null)
            |new ChannelRef[(A, B) :=: TNil](null) ! A
            """.stripMargin)
      }.message must include("The implicit sender `value s` does not support messages of the reply types akka.channels.ChannelSpec.B")
    }

    "permit any sender for Nothing replies" in {
      implicit val s = new ChannelRef[TNil](testActor)
      new ChannelRef[(A, Nothing) :=: TNil](testActor) ! A
      expectMsg(A)
    }

    "require complete reply type sets" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[TNil](null)
            |new ChannelRef[(A, B) :=: (A, C) :=: TNil](null) ! A
            """.stripMargin)
      }.message must include("The implicit sender `value s` does not support messages of the reply types akka.channels.ChannelSpec.B, akka.channels.ChannelSpec.C")
    }

    "not permit nonsensical channel declarations" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :=: TNil] {
            |  channel[B] {
            |    case (B, _) =>
            |  }
            |}
            """.stripMargin)
      }.message must include("no channel defined for type akka.channels.ChannelSpec.B")
    }

    "not permit subchannel replies" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :=: (A1.type, C) :=: TNil] {
            |  channel[A] {
            |    case (A1, x) => x ! C
            |  }
            |}
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.C.type")
    }

    "not permit Nothing children" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :=: (C, D) :=: TNil] {
            |  createChild(new Channels)
            |}
            """.stripMargin)
      }.message must include("Parent argument must not be Nothing")
    }

    "not permit too demanding children" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :=: (C, D) :=: TNil] {
            |  createChild(new Channels[(B, Nothing) :=: TNil, TNil])
            |}
            """.stripMargin)
      }.message must include("This actor cannot support a child requiring channels akka.channels.ChannelSpec.B")
    }

    "have a working selfChannel" in {
      val ref = ChannelExt(system).actorOf(new Children)
      ref ! A
      expectMsg(C)
    }

  }

}