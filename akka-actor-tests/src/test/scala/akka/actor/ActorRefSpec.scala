/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.Timeout
import akka.util.duration._
import java.lang.IllegalStateException
import akka.util.ReflectiveAccess
import akka.serialization.Serialization
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.dispatch.{ Await, DefaultPromise, Promise, Future }
import akka.pattern.ask

object ActorRefSpec {

  case class ReplyTo(sender: ActorRef)

  class ReplyActor extends Actor {
    var replyTo: ActorRef = null

    def receive = {
      case "complexRequest" ⇒ {
        replyTo = sender
        val worker = context.actorOf(Props[WorkerActor])
        worker ! "work"
      }
      case "complexRequest2" ⇒
        val worker = context.actorOf(Props[WorkerActor])
        worker ! ReplyTo(sender)
      case "workDone"      ⇒ replyTo ! "complexReply"
      case "simpleRequest" ⇒ sender ! "simpleReply"
    }
  }

  class WorkerActor() extends Actor {
    import context.system
    def receive = {
      case "work" ⇒ {
        work
        sender ! "workDone"
        context.stop(self)
      }
      case ReplyTo(replyTo) ⇒ {
        work
        replyTo ! "complexReply"
      }
    }

    private def work {
      1.second.dilated.sleep
    }
  }

  class SenderActor(replyActor: ActorRef, latch: TestLatch) extends Actor {

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
      case "self" ⇒ sender ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingOuterActor(val inner: ActorRef) extends Actor {
    val fail = new InnerActor

    def receive = {
      case "self" ⇒ sender ! self
      case x      ⇒ inner forward x
    }
  }

  class FailingInheritingOuterActor(_inner: ActorRef) extends OuterActor(_inner) {
    val fail = new InnerActor
  }

  class InnerActor extends Actor {
    def receive = {
      case "innerself" ⇒ sender ! self
      case other       ⇒ sender ! other
    }
  }

  class FailingInnerActor extends Actor {
    val fail = new InnerActor

    def receive = {
      case "innerself" ⇒ sender ! self
      case other       ⇒ sender ! other
    }
  }

  class FailingInheritingInnerActor extends InnerActor {
    val fail = new InnerActor
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorRefSpec extends AkkaSpec with DefaultTimeout {
  import akka.actor.ActorRefSpec._

  def promiseIntercept(f: ⇒ Actor)(to: Promise[Actor]): Actor = try {
    val r = f
    to.success(r)
    r
  } catch {
    case e ⇒
      to.failure(e)
      throw e
  }

  def wrap[T](f: Promise[Actor] ⇒ T): T = {
    val result = Promise[Actor]()
    val r = f(result)
    Await.result(result, 1 minute)
    r
  }

  "An ActorRef" must {

    "not allow Actors to be created outside of an actorOf" in {
      import system.actorOf
      intercept[akka.actor.ActorInitializationException] {
        new Actor { def receive = { case _ ⇒ } }
      }

      def contextStackMustBeEmpty = ActorCell.contextStack.get.headOption must be === None

      filterException[akka.actor.ActorInitializationException] {
        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new Actor {
              val nested = promiseIntercept(new Actor { def receive = { case _ ⇒ } })(result)
              def receive = { case _ ⇒ }
            })))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(promiseIntercept(new FailingInheritingOuterActor(actorOf(Props(new InnerActor))))(result))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingInheritingOuterActor(actorOf(Props(promiseIntercept(new FailingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(new InnerActor {
              val a = promiseIntercept(new InnerActor)(result)
            }))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new FailingOuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept(new FailingInheritingInnerActor)(result)))))))
        }

        contextStackMustBeEmpty

        intercept[akka.actor.ActorInitializationException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ new InnerActor; new InnerActor })(result)))))))
        }

        contextStackMustBeEmpty
      }

      filterException[java.lang.IllegalStateException] {
        (intercept[java.lang.IllegalStateException] {
          wrap(result ⇒
            actorOf(Props(new OuterActor(actorOf(Props(promiseIntercept({ throw new IllegalStateException("Ur state be b0rked"); new InnerActor })(result)))))))
        }).getMessage must be === "Ur state be b0rked"

        contextStackMustBeEmpty
      }
    }

    "be serializable using Java Serialization on local node" in {
      val a = system.actorOf(Props[InnerActor])

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      Serialization.currentSystem.withValue(system.asInstanceOf[ActorSystemImpl]) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        val readA = in.readObject

        a.isInstanceOf[LocalActorRef] must be === true
        readA.isInstanceOf[LocalActorRef] must be === true
        (readA eq a) must be === true
      }
    }

    "throw an exception on deserialize if no system in scope" in {
      val a = system.actorOf(Props[InnerActor])

      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      out.writeObject(a)

      out.flush
      out.close

      val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))

      (intercept[java.lang.IllegalStateException] {
        in.readObject
      }).getMessage must be === "Trying to deserialize a serialized ActorRef without an ActorSystem in scope." +
        " Use 'akka.serialization.Serialization.currentSystem.withValue(system) { ... }'"
    }

    "must return EmptyLocalActorRef on deserialize if not present in actor hierarchy (and remoting is not enabled)" in {
      import java.io._

      val baos = new ByteArrayOutputStream(8192 * 32)
      val out = new ObjectOutputStream(baos)

      val sysImpl = system.asInstanceOf[ActorSystemImpl]
      val addr = sysImpl.provider.rootPath.address
      val serialized = SerializedActorRef(addr + "/non-existing")

      out.writeObject(serialized)

      out.flush
      out.close

      Serialization.currentSystem.withValue(sysImpl) {
        val in = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray))
        in.readObject must be === new EmptyLocalActorRef(system.eventStream, sysImpl.provider, system.dispatcher, system.actorFor("/").path / "non-existing")
      }
    }

    "support nested actorOfs" in {
      val a = system.actorOf(Props(new Actor {
        val nested = system.actorOf(Props(new Actor { def receive = { case _ ⇒ } }))
        def receive = { case _ ⇒ sender ! nested }
      }))

      val nested = Await.result((a ? "any").mapTo[ActorRef], timeout.duration)
      a must not be null
      nested must not be null
      (a ne nested) must be === true
    }

    "support advanced nested actorOfs" in {
      val a = system.actorOf(Props(new OuterActor(system.actorOf(Props(new InnerActor)))))
      val inner = Await.result(a ? "innerself", timeout.duration)

      Await.result(a ? a, timeout.duration) must be(a)
      Await.result(a ? "self", timeout.duration) must be(a)
      inner must not be a

      Await.result(a ? "msg", timeout.duration) must be === "msg"
    }

    "support reply via sender" in {
      val latch = new TestLatch(4)
      val serverRef = system.actorOf(Props[ReplyActor])
      val clientRef = system.actorOf(Props(new SenderActor(serverRef, latch)))

      clientRef ! "complex"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      latch.reset

      clientRef ! "complex2"
      clientRef ! "simple"
      clientRef ! "simple"
      clientRef ! "simple"

      Await.ready(latch, timeout.duration)

      system.stop(clientRef)
      system.stop(serverRef)
    }

    "stop when sent a poison pill" in {
      val timeout = Timeout(20000)
      val ref = system.actorOf(Props(new Actor {
        def receive = {
          case 5    ⇒ sender.tell("five")
          case null ⇒ sender.tell("null")
        }
      }))

      val ffive = (ref.ask(5)(timeout)).mapTo[String]
      val fnull = (ref.ask(null)(timeout)).mapTo[String]
      ref ! PoisonPill

      Await.result(ffive, timeout.duration) must be("five")
      Await.result(fnull, timeout.duration) must be("null")

      awaitCond(ref.isTerminated, 2000 millis)
    }

    "restart when Kill:ed" in {
      filterException[ActorKilledException] {
        val latch = TestLatch(2)

        val boss = system.actorOf(Props(new Actor {

          override val supervisorStrategy =
            OneForOneStrategy(maxNrOfRetries = 2, withinTimeRange = 1 second)(List(classOf[Throwable]))

          val ref = context.actorOf(
            Props(new Actor {
              def receive = { case _ ⇒ }
              override def preRestart(reason: Throwable, msg: Option[Any]) = latch.countDown()
              override def postRestart(reason: Throwable) = latch.countDown()
            }))

          protected def receive = { case "sendKill" ⇒ ref ! Kill }
        }))

        boss ! "sendKill"
        Await.ready(latch, 5 seconds)
      }
    }
  }
}
