/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.TestEvent._
import java.util.Collection

import scala.concurrent.Await
import akka.pattern.ask
import scala.concurrent.util.duration._

import akka.actor.ActorSystem.Settings
import akka.actor.ActorDSL._
import scala.ref.WeakReference
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

@org.junit.runner.RunWith(classOf[JUnitRunner])
class ActorDSLSpec extends AkkaSpec with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithBoundedStashSpec._
  import java.util.concurrent.TimeUnit._
  implicit val sys = system

  "An actor created with the DSL" must {
    "have a working context" in {
      val latch = new TestLatch

      val actor = actorOf { context ⇒
        {
          case m ⇒
            m must be("hello")
            context.become {
              case m ⇒
                m must be("hello")
                latch.open()
            }
        }
      }

      actor ! "hello"
      actor ! "hello"

      Await.ready(latch, 10 seconds)

      actor ! PoisonPill
    }

    "be able to exchange messages with a regular thread " in {
      val tA = dynamicSelf
      val actor = actorOf { context ⇒
        {
          case m ⇒
            m must be("hello")
            context.sender must be(tA)
            context.sender ! m
        }
      }

      actor ! "hello"

      receive {
        case m ⇒
          m must be("hello")
      }
    }
  }

  "A thread actor" must {
    "support nested receives" in {
      val latch = new TestLatch
      val tA = dynamicSelf
      val actor = actorOf { context ⇒
        {
          case m ⇒
            context.sender must be(tA)
            context.sender ! m
        }
      }

      actor ! "hello"
      actor ! "world"

      receive {
        case "hello" ⇒
          receive {
            case "world" ⇒
              latch.open()
          }
      }

      Await.ready(latch, 10 seconds)
    }

    "only receive user messages" in {
      val tA = dynamicSelf
      val actor = actorOf { context ⇒
        {
          case "start" ⇒
            context.sender must be(tA)
            for (_ ← 0 until 1000)
              context.sender ! "hello"
        }
      }

      actor ! "start"

      for (_ ← 0 until 1000) receive {
        case m ⇒
          m must be("hello")
      }

    }

    "support the ask pattern" in {
      val tA = dynamicSelf

      val actor = actorOf { context ⇒
        {
          case m ⇒
            // send message and wait for response
            val f = context.sender ? "hello"
        }
      }

      actor ! "whatever"

      // wait for a response in a thread
      receive {
        case resp ⇒
          resp must be("hello")
      }
    }

    "support the sender method" in {
      val tA = dynamicSelf
      val latch = new java.util.concurrent.CountDownLatch(2)

      val sender2 = actorOf { context ⇒
        {
          case "start" ⇒
            context.sender ! "hello2"

          case m ⇒
            m must be("sender level 2")
            latch.countDown()
        }
      }

      val sender1 = actorOf { context ⇒
        {
          case "start" ⇒
            // send message and wait for response
            sender ! "hello"

          case "sender level 1" ⇒
            latch.countDown()
        }
      }

      sender1 ! "start"

      // wait for a response in a thread
      receive {
        case resp ⇒
          resp must be("hello")
          sender must be(sender1)
          sender ! "sender level 1"
          sender2 ! "start"
          receive {
            case resp ⇒
              resp must be("hello2")
              sender must be(sender2)
              sender ! "sender level 2"
          }
          sender must be(sender1)
      }
      latch.await(2, SECONDS)
    }

    "must not leak memory" in {
      import scala.collection.JavaConversions._

      def startActors(nr: Int, coll: Collection[WeakReference[AnyRef]]) {
        // create a bunch of thread actors
        var threads = for (_ ← 0 until nr) yield new Thread {
          override def run() = {
            val tA = dynamicSelf
            coll.add(new WeakReference(tA))
          }
        }

        threads.foreach(_.start)
        threads.foreach(_.join)
        threads = null
      }

      // check the weak connection of the whole thread actor object graph
      val threadActors = java.util.Collections.synchronizedCollection(new java.util.ArrayList[WeakReference[AnyRef]]())
      startActors(100, threadActors)
      scala.compat.Platform.collectGarbage()

      threadActors.iterator.forall(x ⇒ x.get == None) must be(true)
    }

    "be reachable with `actorFor` method" in {
      val latch = new TestLatch
      val tA = dynamicSelf
      val actor = actorOf { context ⇒
        {
          case id: Long ⇒
            val threadActor = context.actorFor("/threads/thread-" + id)
            threadActor ! "got your path"
        }
      }

      // send the id by which the thread actor can be looked up
      actor ! Thread.currentThread().getId()

      receive {
        case "got your path" ⇒
          latch.open()
      }

      Await.ready(latch, 2 seconds)
    }

  }
}