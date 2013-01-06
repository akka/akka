/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels

import akka.testkit._
import akka.actor.ActorRef
import akka.makkros.Test._
import scala.tools.reflect.ToolBoxError
import scala.reflect.runtime.{ universe ⇒ ru }
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.Failure
import akka.actor.ActorSystem
import scala.reflect.api.Universe

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
  object C1 extends C

  trait D extends Msg
  object D extends D

  // used for sender verification in the first two test cases
  class Tester extends Channels[TNil, (A, C) :+: (B, D) :+: TNil] {
    channel[A.type] { (msg, snd) ⇒ snd ! C }
    channel[A] { (msg, snd) ⇒ snd ! C1 }
    channel[B] {
      case (B, s) ⇒ s ! D
    }
  }
  class RecvC(ref: ActorRef) extends Channels[TNil, (C, Nothing) :+: TNil] {
    channel[C] { case (x, _) ⇒ ref ! x }
  }

  // pos compile test for multiple reply channels
  class SubChannels extends Channels[TNil, (A, B) :+: (A, C) :+: TNil] {
    channel[A] {
      case (A1, x) ⇒
        x ! B
        x ! C
    }
  }

  // pos compile test for children
  class Children extends Channels[TNil, (A, B) :+: (C, D) :+: TNil] {
    val c = createChild(new Channels[(A, Nothing) :+: TNil, (B, C) :+: TNil] {
      channel[B] { case (B, s) ⇒ s ! C }
    })

    var client: ActorRef = _
    channel[A] {
      case (A, s) ⇒ c ! B; client = sender
    }
    channel[C] {
      case (C, _) ⇒ client ! C
    }

    createChild(new Channels[(C, Nothing) :+: TNil, TNil] {})
    createChild(new Channels[(A, Nothing) :+:(C, Nothing) :+: TNil, TNil] {})
  }

  // compile test for polymorphic actors
  class WriteOnly[T <: ChannelList: ru.TypeTag](target: ChannelRef[T]) extends Channels[TNil, (D, D) :+: T] {
    channel[D] { (d, snd) ⇒ snd ! d }
    channel[T] { x ⇒ target forward x }
  }
}

class ChannelSpec extends AkkaSpec(ActorSystem("ChannelSpec", AkkaSpec.testConf, classOf[AkkaSpec].getClassLoader)) with ImplicitSender {

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

    "correctly dispatch to subchannels" in {
      val ref = ChannelExt(system).actorOf(new Tester)
      implicit val sender = ChannelExt(system).actorOf(new RecvC(testActor))
      ref ! A2
      expectMsg(C1)
      lastSender must be(sender.actorRef)
    }

    "not permit wrong message type" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :+: TNil](null) ! B
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.B.type")
    }

    "not permit wrong message type in complex channel" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :+: (B, D) :+: TNil](null) ! C
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.C.type")
    }

    "not permit unfit sender ref" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(C, D) :+: TNil](null)
            |new ChannelRef[(A, B) :+: TNil](null) ! A
            """.stripMargin)
      }.message must include("The implicit sender `value s` does not support messages of the reply types akka.channels.ChannelSpec.B")
    }

    "permit any sender for Nothing replies" in {
      implicit val s = new ChannelRef[TNil](testActor)
      new ChannelRef[(A, Nothing) :+: TNil](testActor) ! A
      expectMsg(A)
    }

    "require complete reply type sets" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[TNil](null)
            |new ChannelRef[(A, B) :+: (A, C) :+: TNil](null) ! A
            """.stripMargin)
      }.message must include("The implicit sender `value s` does not support messages of the reply types akka.channels.ChannelSpec.B, akka.channels.ChannelSpec.C")
    }

    "not permit nonsensical channel declarations" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :+: TNil] {
            |  channel[B] {
            |    case (B, _) =>
            |  }
            |}
            """.stripMargin)
      }.message must include("no channel defined for types akka.channels.ChannelSpec.B")
    }

    "not permit subchannel replies" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :+: (A1.type, C) :+: TNil] {
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
            |new Channels[TNil, (A, B) :+: (C, D) :+: TNil] {
            |  createChild(new Channels[Nothing, Nothing] {})
            |}
            """.stripMargin)
      }.message must include("Parent argument must not be Nothing")
    }

    "not permit too demanding children" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, B) :+: (C, D) :+: TNil] {
            |  createChild(new Channels[(B, Nothing) :+: TNil, TNil] {})
            |}
            """.stripMargin)
      }.message must include("This actor cannot support a child requiring channels akka.channels.ChannelSpec.B")
    }

    "have a working selfChannel" in {
      val ref = ChannelExt(system).actorOf(new Children)
      ref ! A
      expectMsg(C)
    }

    "have a working parentChannel" in {
      val parent = ChannelExt(system).actorOf(new Channels[TNil, (A, Nothing) :+: TNil] {
        createChild(new Channels[(A, Nothing) :+: TNil, TNil] {
          parentChannel ! A
        })
        channel[A] { (msg, snd) ⇒ testActor ! msg }
      })
      expectMsg(A)
    }

    "not permit top-level Channels which send to parent" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |null.asInstanceOf[ChannelExtension].actorOf(new Channels[(A, A) :+: TNil, (A, Nothing) :+: TNil] {})
            """.stripMargin)
      }.message must include("type mismatch")
    }

    "not permit sending wrong things to parents" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Channels[TNil, (A, Nothing) :+: TNil] {
            |  createChild(new Channels[(A, Nothing) :+: TNil, TNil] {
            |    parentChannel ! B
            |  })
            |}
            """.stripMargin)
      }.message must include("This ChannelRef does not support messages of type akka.channels.ChannelSpec.B.type")
    }

    "support narrowing of references" in {
      val ref = new ChannelRef[(A, B) :+:(C, D) :+: TNil](null)
      val n: ChannelRef[(A1.type, B) :+: TNil] = ref.narrow[(A1.type, B) :+: TNil]
    }

    "not allow narrowed refs to open new channels" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :+: TNil](null).narrow[(A, C) :+: (B, C) :+: TNil]
            """.stripMargin)
      }.message must include("original ChannelRef does not support input type akka.channels.ChannelSpec.B")
    }

    "not allow narrowed refs to widen channels" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A1.type, C) :+: TNil](null).narrow[(A, C) :+: TNil]
            """.stripMargin)
      }.message must include("original ChannelRef does not support input type akka.channels.ChannelSpec.A")
    }

    "not allow narrowed refs to miss reply channels" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :+: (A, D) :+: TNil](null).narrow[(A, C) :+: TNil]
            """.stripMargin)
      }.message must include("reply types akka.channels.ChannelSpec.D not covered for channel akka.channels.ChannelSpec.A")
    }

    "not allow narrowed refs to narrow reply channels" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new ChannelRef[(A, C) :+: (B, D) :+: TNil](null).narrow[(A, C) :+: (A, Nothing) :+: TNil]
            """.stripMargin)
      }.message must include("reply types Nothing are superfluous for channel akka.channels.ChannelSpec.A")
    }

    "support narrowing ActorRefs" in {
      import Channels._
      val channel = ChannelExt(system).actorOf(new RecvC(testActor))
      val ref = channel.actorRef
      implicit val t = Timeout(1.second.dilated)
      import system.dispatcher
      val r = Await.result(ref.narrow[(C, Nothing) :+: TNil], t.duration)
      r ! C
      expectMsg(C)
    }

    "deny wrong narrowing of ActorRefs" in {
      val channel = ChannelExt(system).actorOf(new RecvC(testActor))
      val ref = channel.actorRef
      implicit val t = Timeout(1.second.dilated)
      import system.dispatcher
      val f = ref.narrow[(D, Nothing) :+: TNil]
      Await.ready(f, t.duration)
      f.value.get must be(Failure(Channels.NarrowingException("original ChannelRef does not support input type akka.channels.ChannelSpec.D")))
    }

    "be equal according to its actor" in {
      val c1, c2 = new ChannelRef[TNil](testActor)
      c1 must be === c2
    }

    "allow wrapping of ChannelRefs with pass-through" in {
      val target = ChannelExt(system).actorOf(new RecvC(testActor))
      val wrap = ChannelExt(system).actorOf(new WriteOnly(target))
      wrap ! C
      expectMsg(C)
      lastSender must be(target.actorRef)
      wrap ! D
      expectMsg(D)
      lastSender must be(wrap.actorRef)
    }

  }

}