/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._
import akka.testkit.Testing.sleepFor
import java.lang.IllegalStateException
import akka.util.ReflectiveAccess
import akka.dispatch.{ DefaultPromise, Promise, Future }
import akka.serialization.Serialization
import java.util.concurrent.{ CountDownLatch, TimeUnit }

object ActorRefSpec {

  case class ReplyTo(channel: Channel[Any])

  val latch = TestLatch(4)

  class ReplyActor extends Actor {
    var replyTo: Channel[Any] = null

    def receive = {
      case "complexRequest" ⇒ {
        replyTo = channel
        val worker = context.actorOf(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = context.actorOf(Props[WorkerActor])
        worker ! ReplyTo(channel)
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ channel ! "simpleReply"
    }
  }

  class WorkerActor() extends Actor {
    def receive = {
      case "work" ⇒ {
        work
        channel ! "workDone"
        self.stop()
      }
      case ReplyTo(replyTo) ⇒ {
        work
        replyTo ! "complexReply"
      }
    }

    private def work {
      sleepFor(1 second)
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {

    def receive = {
      case "complex"  ⇒ replyActor ! "complexRequest"
      case "complex2" ⇒ replyActor ! "complexRequest2"
      case "simple"   ⇒ replyActor ! "simpleRequest"
      case "complexReply" ⇒ {
        latch.countDown()
      }
      case "simpleReply" ⇒ {
        latch.countDown()
      }
    }
  }

  class OuterActor(val inner: ActorRef) extends Actor {
    def receive = {
      case "self" ⇒ channel ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingOuterActor(val inner: ActorRef) extends Actor {
    val fail = new InnerActor

    def receive = {
      case "self" ⇒ channel ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingInheritingOuterActor(_inner: ActorRef) extends OuterActor(_inner) {
    val fail = new InnerActor
  }

  class InnerActor extends Actor {
    def receive = {
      case "innerself" ⇒ channel ! self
      case other       ⇒ channel ! other
    }
  }

  class FailingInnerActor extends Actor {
    val fail = new InnerActor

    def receive = {
      case "innerself" ⇒ channel ! self
      case other       ⇒ channel ! other
    }
  }

  class FailingInheritingInnerActor extends InnerActor {
    val fail = new InnerActor
  }
}

class ActorRefSpec extends AkkaSpec {
  import akka.actor.ActorRefSpec._

  def promiseIntercept(f: ⇒ Actor)(to: Promise[Actor]): Actor = try {
    val r = f
    to.completeWithResult(r)
    r
  } catch {
    case e ⇒
      to.completeWithException(e)
      throw e
  }

  def wrap[T](f: Promise[Actor] ⇒ T): T = {
    val result = new DefaultPromise[Actor](10 * 60 * 1000)
    val r = f(result)
    result.get
    r
  }

  "An ActorRef" must {

    "not allow Actors to be created outside of an actorOf" in {
      intercept[akka.actor.ActorInitializationException] {
        new Actor { def receive = { case _ ⇒ } }
      }

      def contextStackMustBeEmpty = ActorCell.contextStack.get.headOption must be === None

      filterException[akka.actor.ActorInitializationException] {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new Actor {
              val nested = promiseIntercept(new Actor { def receive = { case _ ⇒ } })(result)
              def receive = { case _ ⇒ }
            }))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(promiseIntercept(new FailingOuterActor(actorOf(new InnerActor)))(result)))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new OuterActor(actorOf(promiseIntercept(new FailingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(promiseIntercept(new FailingInheritingOuterActor(actorOf(new InnerActor)))(result)))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new FailingOuterActor(actorOf(promiseIntercept(new FailingInheritingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new FailingInheritingOuterActor(actorOf(promiseIntercept(new FailingInheritingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new FailingInheritingOuterActor(actorOf(promiseIntercept(new FailingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new OuterActor(actorOf(new InnerActor {
              val a = promiseIntercept(new InnerActor)(result)
            }))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new FailingOuterActor(actorOf(promiseIntercept(new FailingInheritingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new OuterActor(actorOf(promiseIntercept(new FailingInheritingInnerActor)(result)))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(new OuterActor(actorOf(promiseIntercept({ new InnerActor; new InnerActor })(result)))))
        }

        contextStackMustBeEmpty
      }

      filterException[java.lang.IllegalStateException] {
        (intercept[java.lang.IllegalStateException] {
          wrap(result ⇒
            actorOf(new OuterActor(actorOf(promiseIntercept({ throw new IllegalStateException("Ur state be b0rked"); new InnerActor })(result)))))
        }).getMessage must be === "Ur state be b0rked"

        contextStackMustBeEmpty
      }
    }

    "be serializable using Java Serialization on local node" in {
      val a = actorOf[InnerActor]

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      Serialization.app.withValue(app) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        val readA = in.readObject

        a.isInstanceOf[LocalActorRef] must be === true
        readA.isInstanceOf[LocalActorRef] must be === true
        (readA eq a) must be === true
      }
    }

    "throw an exception on deserialize if no app in scope" in {
      val a = actorOf[InnerActor]

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

      (intercept[java.lang.IllegalStateException] {
        in.readObject
      }).getMessage must be === "Trying to deserialize a serialized ActorRef without an AkkaApplication in scope." +
        " Use akka.serialization.Serialization.app.withValue(akkaApplication) { ... }"
    }

    "must throw exception on deserialize if not present in local registry and remoting is not enabled" in {
      val latch = new CountDownLatch(1)
      val a = actorOf(new InnerActor {
        override def postStop {
          // app.registry.unregister(self)
          latch.countDown
        }
      })

      val inetAddress = app.defaultAddress

      val expectedSerializedRepresentation = SerializedActorRef(
        a.uuid,
        a.address,
        inetAddress.getAddress.getHostAddress,
        inetAddress.getPort)

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      a.stop()
      latch.await(5, TimeUnit.SECONDS) must be === true

      Serialization.app.withValue(app) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        (intercept[java.lang.IllegalStateException] {
          in.readObject
        }).getMessage must be === "Could not deserialize ActorRef"
      }
    }

    "support nested actorOfs" in {
      val a = actorOf(new Actor {
        val nested = actorOf(new Actor { def receive = { case _ ⇒ } })
        def receive = { case _ ⇒ channel ! nested }
      })

      val nested = (a ? "any").as[ActorRef].get
      a must not be null
      nested must not be null
      (a ne nested) must be === true
    }

    "support advanced nested actorOfs" in {
      val a = actorOf(Props(new OuterActor(actorOf(Props(new InnerActor)))))
      val inner = (a ? "innerself").as[Any].get

      (a ? a).as[ActorRef].get must be(a)
      (a ? "self").as[ActorRef].get must be(a)
      inner must not be a

      (a ? "msg").as[String] must be === Some("msg")
    }

    "support reply via channel" in {
      val serverRef = actorOf(Props[ReplyActor])
      val clientRef = actorOf(Props(new SenderActor(serverRef)))

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      latch.await

      latch.reset

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      latch.await

      clientRef.stop()
      serverRef.stop()
    }

    "stop when sent a poison pill" in {
      val timeout = Timeout(20000)
      val ref = actorOf(Props(new Actor {
        def receive = {
          case 5    ⇒ channel.tryTell("five")
          case null ⇒ channel.tryTell("null")
        }
      }))

      val ffive = (ref ? (5, timeout)).mapTo[String]
      val fnull = (ref ? (null, timeout)).mapTo[String]

      intercept[ActorKilledException] {
        (ref ? PoisonPill).get
        fail("shouldn't get here")
      }

      ffive.get must be("five")
      fnull.get must be("null")

      awaitCond(ref.isShutdown, 100 millis)
    }

    "restart when Kill:ed" in {
      filterException[ActorKilledException] {
        val latch = new CountDownLatch(2)

        val boss = actorOf(Props(new Actor {

          val ref = context.actorOf(
            Props(new Actor {
              def receive = { case _ ⇒ }
              override def preRestart(reason: Throwable, msg: Option[Any]) = latch.countDown()
              override def postRestart(reason: Throwable) = latch.countDown()
            }))

          protected def receive = { case "sendKill" ⇒ ref ! Kill }
        }).withFaultHandler(OneForOneStrategy(List(classOf[Throwable]), 2, 1000)))

        boss ! "sendKill"
        latch.await(5, TimeUnit.SECONDS) must be === true
      }
    }
  }
}
