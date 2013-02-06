/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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
import scala.concurrent.Future
import akka.actor.ActorInitializationException
import akka.actor.Props
import akka.actor.Actor
import akka.actor.OneForOneStrategy
import akka.actor.SupervisorStrategy.Stop
import java.lang.reflect.InvocationTargetException

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
  class Tester extends Actor with Channels[TNil, (A, C) :+: (B, D) :+: TNil] {
    channel[A.type] { (msg, snd) ⇒ snd <-!- C }
    channel[A] { (msg, snd) ⇒ snd <-!- C1 }
    channel[B] {
      case (B, s) ⇒ s <-!- D
    }
  }
  class RecvC(ref: ActorRef) extends Actor with Channels[TNil, (C, Nothing) :+: TNil] {
    channel[C] { case (x, _) ⇒ ref ! x }
  }

  // pos compile test for multiple reply channels
  class SubChannels extends Actor with Channels[TNil, (A, B) :+: (A, C) :+: TNil] {
    channel[A] {
      case (A1, x) ⇒ B -!-> x
      case (_, x)  ⇒ x <-!- C
    }
  }

  // pos compile test for children
  class Children extends Actor with Channels[TNil, (A, B) :+: (C, Nothing) :+: TNil] {
    val c = createChild(new Actor with Channels[(A, Nothing) :+: TNil, (B, C) :+: TNil] {
      channel[B] { case (B, s) ⇒ s <-!- C }
    })

    var client: ActorRef = _
    channel[A] {
      case (A, s) ⇒ c <-!- B; client = sender
    }
    channel[C] {
      case (C, _) ⇒ client ! C
    }

    createChild(new Actor with Channels[(C, Nothing) :+: TNil, TNil] {})
    createChild(new Actor with Channels[(A, Nothing) :+:(C, Nothing) :+: TNil, TNil] {})
  }

  // compile test for polymorphic actors
  class WriteOnly[T1: ru.TypeTag, T2: ru.TypeTag](target: ChannelRef[(T1, T2) :+: TNil]) extends Actor with Channels[TNil, (D, D) :+: (T1, T2) :+: TNil] {
    implicit val t = Timeout(1.second)
    import akka.pattern.ask

    channel[D] { (d, snd) ⇒ snd <-!- d }

    channel[T1] { (x, snd) ⇒ x -?-> target -!-> snd -!-> snd }
  }

  // compile test for whole-channel polymorphism
  class Poly[T <: ChannelList: ru.TypeTag](target: ChannelRef[T]) extends Actor with Channels[TNil, (A, A) :+: (B, B) :+: T] {
    implicit val timeout = Timeout(1.second)
    channel[T] { (x, snd) ⇒
      val xx: WrappedMessage[T, Any] = x
      val f: Future[ReplyChannels[T]] = target <-?- x
      f -!-> snd
    }
    import language.existentials
    channel[(A, _) :+: (B, _) :+: TNil] { (x, snd) ⇒
      val m: Msg = x.value
      val c: ChannelRef[TNil] = snd
    }
    channel[A] { (x, snd) ⇒ x -!-> snd }
  }

  // companion to WriteOnly for testing pass-through
  class EchoTee(target: ActorRef) extends Actor with Channels[TNil, (C, C) :+: TNil] {
    channel[C] { (c, snd) ⇒ target ! C1; snd <-!- C1 }
  }

  class MissingChannel extends Actor with Channels[TNil, (A, A) :+: (B, B) :+: TNil] {
    channel[A.type] { (_, _) ⇒ }
  }

}

class ChannelSpec extends AkkaSpec(ActorSystem("ChannelSpec", AkkaSpec.testConf, classOf[AkkaSpec].getClassLoader)) with ImplicitSender {

  import ChannelSpec._

  implicit val selfChannel = new ChannelRef[(Any, Nothing) :+: TNil](testActor)

  "Actor with Channels" must {

    "construct refs" in {
      val ref = ChannelExt(system).actorOf(new Tester, "t1")
      ref <-!- A
      expectMsg(C)
      lastSender must be(ref.actorRef)
      ref <-!- B
      expectMsg(D)
      lastSender must be(ref.actorRef)
    }

    "select return channels" in {
      val ref = ChannelExt(system).actorOf(new Tester, "t2")
      implicit val selfChannel = ChannelExt(system).actorOf(new RecvC(testActor), "t3")
      ref <-!- A
      expectMsg(C)
      lastSender must be(selfChannel.actorRef)
    }

    "correctly dispatch to subchannels" in {
      val ref = ChannelExt(system).actorOf(new Tester, "t4")
      implicit val selfChannel = ChannelExt(system).actorOf(new RecvC(testActor), "t5")
      ref <-!- A2
      expectMsg(C1)
      lastSender must be(selfChannel.actorRef)
    }

    "not permit wrong message type" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val c = new ChannelRef[TNil](null)
            |new ChannelRef[(A, C) :+: TNil](null) <-!- B
            """.stripMargin)
      }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B.type")
    }

    "not permit wrong message type in complex channel" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val c = new ChannelRef[TNil](null)
            |new ChannelRef[(A, C) :+: (B, D) :+: TNil](null) <-!- C
            """.stripMargin)
      }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.C.type")
    }

    "not permit unfit sender ref" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(C, D) :+: TNil](null)
            |new ChannelRef[(A, B) :+: TNil](null) <-!- A
            """.stripMargin)
      }.message must include("implicit sender `s` does not support messages of the reply types akka.channels.ChannelSpec.B")
    }

    "permit any sender for Nothing replies" in {
      implicit val selfChannel = new ChannelRef[TNil](testActor)
      new ChannelRef[(A, Nothing) :+: TNil](testActor) <-!- A
      expectMsg(A)
    }

    "require complete reply type sets" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[TNil](null)
            |new ChannelRef[(A, B) :+: (A, C) :+: TNil](null) <-!- A
            """.stripMargin)
      }.message must include("implicit sender `s` does not support messages of the reply types akka.channels.ChannelSpec.B, akka.channels.ChannelSpec.C")
    }

    "verify ping-pong chains" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(B, B) :+: TNil](null)
            |new ChannelRef[(A, B) :+: (B, C) :+: TNil](null) <-!- A
            """.stripMargin)
      }.message must include("implicit sender `s` does not support messages of the reply types akka.channels.ChannelSpec.C")
    }

    "tolerate infinite ping-pong" in {
      val ex = intercept[InvocationTargetException] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(B, B) :+: (C, B) :+: TNil](null)
            |new ChannelRef[(A, B) :+: (B, C) :+: TNil](null) <-!- A
            """.stripMargin)
      }
      def cause(ex: Throwable): Throwable =
        if (ex.getCause == null) ex else cause(ex.getCause)
      cause(ex).getClass must be(classOf[NullPointerException])
    }

    "not permit nonsensical channel declarations" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Actor with Channels[TNil, (A, B) :+: TNil] {
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
            |new Actor with Channels[TNil, (A, B) :+: (A1.type, C) :+: TNil] {
            |  channel[A] {
            |    case (A1, x) => x <-!- C
            |  }
            |}
            """.stripMargin)
      }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.C.type")
    }

    "not permit Nothing children" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import akka.actor.Actor
            |import ChannelSpec._
            |new Actor with Channels[TNil, (A, B) :+: (C, D) :+: TNil] {
            |  createChild(new Actor with Channels[Nothing, Nothing] {})
            |}
            """.stripMargin)
      }.message must include("Parent argument must not be Nothing")
    }

    "not permit too demanding children" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import akka.actor.Actor
            |import ChannelSpec._
            |new Actor with Channels[TNil, (A, B) :+: (C, D) :+: TNil] {
            |  createChild(new Actor with Channels[(B, Nothing) :+: TNil, TNil] {})
            |}
            """.stripMargin)
      }.message must include("This actor cannot support a child requiring channels akka.channels.ChannelSpec.B")
    }

    "have a working selfChannel" in {
      val ref = ChannelExt(system).actorOf(new Children, "t10")
      ref <-!- A
      expectMsg(C)
    }

    "have a working parentChannel" in {
      val parent = ChannelExt(system).actorOf(new Actor with Channels[TNil, (A, Nothing) :+: TNil] {
        createChild(new Actor with Channels[(A, Nothing) :+: TNil, TNil] {
          parentChannel <-!- A
        })
        channel[A] { (msg, snd) ⇒ testActor ! msg }
      }, "t11")
      expectMsg(A)
    }

    "not permit top-level Actor with Channels which send to parent" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |null.asInstanceOf[ChannelExtension].actorOf(new Actor with Channels[(A, A) :+: TNil, (A, Nothing) :+: TNil] {}, "")
            """.stripMargin)
      }.message must include("type mismatch")
    }

    "not permit sending wrong things to parents" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Actor with Channels[TNil, (A, Nothing) :+: TNil] {
            |  createChild(new Actor with Channels[(A, Nothing) :+: TNil, TNil] {
            |    parentChannel <-!- B
            |  })
            |}
            """.stripMargin)
      }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B.type")
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
      val channel = ChannelExt(system).actorOf(new RecvC(testActor), "t15")
      val ref = channel.actorRef
      implicit val t = Timeout(1.second.dilated)
      import system.dispatcher
      val r = Await.result(ref.narrow[(C, Nothing) :+: TNil], t.duration)
      r <-!- C
      expectMsg(C)
    }

    "deny wrong narrowing of ActorRefs" in {
      val channel = ChannelExt(system).actorOf(new RecvC(testActor), "t16")
      val ref = channel.actorRef
      implicit val t = Timeout(1.second.dilated)
      import system.dispatcher
      val f = ref.narrow[(D, Nothing) :+: TNil]
      Await.ready(f, t.duration)
      f.value.get must be(Failure(NarrowingException("original ChannelRef does not support input type akka.channels.ChannelSpec.D")))
    }

    "be equal according to its actor" in {
      val c1, c2 = new ChannelRef[TNil](testActor)
      c1 must be === c2
    }

    "allow wrapping of ChannelRefs with pass-through" in {
      val target = ChannelExt(system).actorOf(new RecvC(testActor), "t17")
      val wrap = ChannelExt(system).actorOf(new WriteOnly[C, Nothing](target), "t18")
      wrap <-!- C
      expectMsg(C)
      lastSender must be(target.actorRef)
      wrap <-!- D
      expectMsg(D)
      lastSender must be(wrap.actorRef)
    }

    "allow wrapping of Actor with ChannelsRefs with replies" in {
      val probe = TestProbe()
      val target = ChannelExt(system).actorOf(new EchoTee(probe.ref), "t19")
      val wrap = ChannelExt(system).actorOf(new WriteOnly[C, C](target), "t20")
      C -!-> wrap
      expectMsg(C1)
      expectMsg(C1)
      probe.expectMsg(C1)
    }

    "support typed ask" in {
      val t = ChannelExt(system).actorOf(new Tester, "t21")
      implicit val timeout = Timeout(1.second)
      val r: Future[C] = t <-?- A
      Await.result(r, 1.second) must be(C)
    }

    "support typed ask with multiple reply channels" in {
      val t = ChannelExt(system).actorOf(new SubChannels, "t22")
      implicit val timeout = Timeout(1.second)
      val r: Future[Msg] = (t <-?- A1).lub
      Await.result(r, 1.second) must be(B)
    }

    "check that channels do not erase to the same types" in {
      val m = intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |new Actor with Channels[TNil, (List[A], A) :+: (List[B], B) :+: TNil] {
            |  channel[List[A]] { (x, s) ⇒ }
            |  channel[List[B]] { (x, s) ⇒ }
            |}
            """.stripMargin)
      }.message
      m must include("erasure List[Any] overlaps with declared channels List[akka.channels.ChannelSpec.A]")
      m must include("erasure List[Any] overlaps with declared channels List[akka.channels.ChannelSpec.B]")
    }

    "check that all channels were declared" in {
      EventFilter[ActorInitializationException](occurrences = 1) intercept {
        system.actorOf(Props(new Actor {
          context.actorOf(Props[MissingChannel])
          override val supervisorStrategy = OneForOneStrategy() {
            case ex: ActorInitializationException ⇒ testActor ! ex.getCause; Stop
          }
          def receive = {
            case _ ⇒
          }
        }))
      }
      val m = expectMsgType[ActorInitializationException].getMessage
      m must include("missing declarations for channels")
      m must include("akka.channels.ChannelSpec.A")
      m must include("akka.channels.ChannelSpec.B")
    }

    "be able to forward fully generic channels" in {
      val cd = ChannelExt(system).actorOf(new Actor with Channels[TNil, (C, D) :+: TNil] {
        channel[C] { (x, snd) ⇒ snd <-!- D }
      }, "t25")
      val t = ChannelExt(system).actorOf(new Poly(cd), "t26")
      t <-!- A
      expectMsg(A)
      t <-!- C
      expectMsg(D)
      lastSender must be === t.actorRef
    }

    "not wrap Futures unnecessarily" in {
      val a = ChannelExt(system).actorOf(new Tester, "t26a")
      implicit val timeout = Timeout(1.second)
      val c1: C = Await.result(a <-?- A, 1.second)
      val c2: C = Await.result(A -?-> a, 1.second)
      val fA = Future successful A
      val c3: C = Await.result(a <-?- fA, 1.second)
      val c4: C = Await.result(fA -?-> a, 1.second)
    }

    "be able to transform Futures" in {
      val client = new ChannelRef[(Any, Nothing) :+: TNil](testActor)
      val someActor = ChannelExt(system).actorOf(new Tester, "t26b")
      implicit val timeout = Timeout(1.second)
      implicit val ec = system.dispatcher
      A -?-> someActor -*-> (_ map { case C ⇒ B }) -?-> someActor -!-> client
      expectMsg(D)
    }

  }

  "A WrappedMessage" must {

    "be sendable to a ChannelRef" in {
      implicit val selfChannel = ChannelExt(system).actorOf(new Actor with Channels[TNil, (C, Nothing) :+:(D, Nothing) :+: TNil] {
        channel[C] { (c, snd) ⇒ testActor ! c }
        channel[D] { (d, snd) ⇒ testActor ! d }
      }, "t27")
      val t = ChannelExt(system).actorOf(new Tester, "t28")
      val a = new WrappedMessage[(A, Nothing) :+:(B, Nothing) :+: TNil, Msg](A)
      val fa = Future successful a
      val b = new WrappedMessage[(A, Nothing) :+:(B, Nothing) :+: TNil, Msg](B)
      val fb = Future successful b
      t <-!- a
      expectMsg(C)
      a -!-> t
      expectMsg(C)
      t <-!- b
      expectMsg(D)
      b -!-> t
      expectMsg(D)
      t <-!- fa
      expectMsg(C)
      fa -!-> t
      expectMsg(C)
      t <-!- fb
      expectMsg(D)
      fb -!-> t
      expectMsg(D)
    }

    "not be sendable with wrong channels" when {
      "sending wrong first directly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(Any, Nothing) :+: TNil](null)
            |new ChannelRef[(A, Nothing) :+: TNil](null) <-!- new WrappedMessage[(B, Nothing) :+: (A, Nothing) :+: TNil, Msg](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong first indirectly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(Any, Nothing) :+: TNil](null)
            |new WrappedMessage[(B, Nothing) :+: (A, Nothing) :+: TNil, Msg](null) -!-> new ChannelRef[(A, Nothing) :+: TNil](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong second directly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(Any, Nothing) :+: TNil](null)
            |new ChannelRef[(A, Nothing) :+: TNil](null) <-!- new WrappedMessage[(A, Nothing) :+: (B, Nothing) :+: TNil, Msg](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong second indirectly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val s = new ChannelRef[(Any, Nothing) :+: TNil](null)
            |new WrappedMessage[(A, Nothing) :+: (B, Nothing) :+: TNil, Msg](null) -!-> new ChannelRef[(A, Nothing) :+: TNil](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
    }

    "be askable to a ChannelRef" in {
      implicit val timeout = Timeout(1.second)
      val t = ChannelExt(system).actorOf(new Tester, "t30")
      val a = new WrappedMessage[(A, Nothing) :+:(B, Nothing) :+: TNil, Msg](A)
      val fa = Future successful a
      val b = new WrappedMessage[(A, Nothing) :+:(B, Nothing) :+: TNil, Msg](B)
      val fb = Future successful b
      (Await.result(t <-?- a, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(C)
      (Await.result(a -?-> t, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(C)
      (Await.result(t <-?- b, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(D)
      (Await.result(b -?-> t, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(D)
      (Await.result(t <-?- fa, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(C)
      (Await.result(fa -?-> t, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(C)
      (Await.result(t <-?- fb, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(D)
      (Await.result(fb -?-> t, timeout.duration): WrappedMessage[(C, Nothing) :+: (D, Nothing) :+: TNil, Msg]).value must be(D)
    }

    "not be askable with wrong channels" when {
      "sending wrong first directly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val t = akka.util.Timeout(null)
            |new ChannelRef[(A, Nothing) :+: TNil](null) <-?- new WrappedMessage[(B, Nothing) :+: (A, Nothing) :+: TNil, Msg](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong first indirectly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val t = akka.util.Timeout(null)
            |new WrappedMessage[(B, Nothing) :+: (A, Nothing) :+: TNil, Msg](null) -?-> new ChannelRef[(A, Nothing) :+: TNil](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong second directly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val t = akka.util.Timeout(null)
            |new ChannelRef[(A, Nothing) :+: TNil](null) <-?- new WrappedMessage[(A, Nothing) :+: (B, Nothing) :+: TNil, Msg](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
      "sending wrong second indirectly" in {
        intercept[ToolBoxError] {
          eval("""
            |import akka.channels._
            |import ChannelSpec._
            |implicit val t = akka.util.Timeout(null)
            |new WrappedMessage[(A, Nothing) :+: (B, Nothing) :+: TNil, Msg](null) -?-> new ChannelRef[(A, Nothing) :+: TNil](null)
            """.stripMargin)
        }.message must include("target ChannelRef does not support messages of types akka.channels.ChannelSpec.B (at depth 1)")
      }
    }

    "be LUBbable within a Future" in {
      implicit val timeout = Timeout(1.second)
      val t = ChannelExt(system).actorOf(new Tester, "t31")
      val a = new WrappedMessage[(A, Nothing) :+:(B, Nothing) :+: TNil, Msg](A)
      (Await.result((a -?-> t).lub, timeout.duration): Msg) must be(C)
    }

    "not be LUBbable if not wrapped" in {
      intercept[ToolBoxError] {
        eval("""
            |import akka.channels._
            |import ChannelSpec._
            |import scala.concurrent.Future
            |null.asInstanceOf[Future[Int]].lub
            """.stripMargin)
      }.message must include("Cannot prove that Int <:< akka.channels.WrappedMessage")
    }

  }

}