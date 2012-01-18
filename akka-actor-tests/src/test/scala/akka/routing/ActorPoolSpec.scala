package akka.routing

import akka.actor._
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import akka.testkit.AkkaSpec
import akka.dispatch.{ Await, Promise, Future }
import akka.pattern.ask

object ActorPoolSpec {

  trait Foo {
    def sq(x: Int, sleep: Long): Future[Int]
  }

  class FooImpl extends Foo {
    import TypedActor.dispatcher
    def sq(x: Int, sleep: Long): Future[Int] = {
      if (sleep > 0) Thread.sleep(sleep)
      Promise.successful(x * x)
    }
  }

  val faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, 1000)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TypedActorPoolSpec extends AkkaSpec with DefaultTimeout {
  import ActorPoolSpec._
  "Actor Pool (2)" must {
    "support typed actors" in {
      val ta = TypedActor(system)
      val pool = ta.createProxy[Foo](new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
        val typedActor = TypedActor(context)
        def lowerBound = 1
        def upperBound = 5
        def pressureThreshold = 1
        def partialFill = true
        def selectionCount = 1
        def rampupRate = 0.1
        def backoffRate = 0.50
        def backoffThreshold = 0.50
        def instance(p: Props) = typedActor.getActorRefFor(typedActor.typedActorOf[Foo, FooImpl](props = p.withTimeout(10 seconds)))
        def receive = _route
      }, Props().withTimeout(10 seconds).withFaultHandler(faultHandler))

      val results = for (i ← 1 to 100) yield (i, pool.sq(i, 0))

      for ((i, r) ← results)
        Await.result(r, timeout.duration) must equal(i * i)

      ta.stop(pool)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorPoolSpec extends AkkaSpec with DefaultTimeout {
  import ActorPoolSpec._

  "Actor Pool" must {

    "have expected capacity" in {
      val latch = TestLatch(2)
      val count = new AtomicInteger(0)

      val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with SmallestMailboxSelector {
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                count.incrementAndGet
                latch.countDown()
                sender.tell("success")
            }
          }))

          def limit = 2
          def selectionCount = 1
          def partialFill = true
          def receive = _route
        }).withFaultHandler(faultHandler))

      val successes = TestLatch(2)
      val successCounter = system.actorOf(Props(new Actor {
        def receive = {
          case "success" ⇒ successes.countDown()
        }
      }))

      implicit val replyTo = successCounter
      pool ! "a"
      pool ! "b"

      Await.ready(latch, TestLatch.DefaultTimeout)
      Await.ready(successes, TestLatch.DefaultTimeout)

      count.get must be(2)

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be(2)

      system.stop(pool)
    }

    "pass ticket #705" in {
      val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicFilter {
          def lowerBound = 2
          def upperBound = 20
          def rampupRate = 0.1
          def backoffRate = 0.1
          def backoffThreshold = 0.5
          def partialFill = true
          def selectionCount = 1
          def receive = _route
          def pressureThreshold = 1
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case req: String ⇒ {
                (10 millis).dilated.sleep
                sender.tell("Response")
              }
            }
          }))
        }).withFaultHandler(faultHandler))

      try {
        (for (count ← 1 to 500) yield pool.?("Test", 20 seconds)) foreach {
          Await.result(_, 20 seconds) must be("Response")
        }
      } finally {
        system.stop(pool)
      }
    }

    "grow as needed under pressure" in {
      // make sure the pool starts at the expected lower limit and grows to the upper as needed
      // as influenced by the backlog of blocking pooled actors

      var latch = TestLatch(3)
      val count = new AtomicInteger(0)

      val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveActorsPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                count.incrementAndGet
                latch.countDown()
            }
          }))

          def lowerBound = 2
          def upperBound = 4
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def receive = _route
        }).withFaultHandler(faultHandler))

      // first message should create the minimum number of delgates

      pool ! 1

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be(2)

      var loops = 0
      def loop(t: Int) = {
        latch = TestLatch(loops)
        count.set(0)
        for (m ← 0 until loops) {
          pool ? t
          (50 millis).dilated.sleep
        }
      }

      // 2 more should go thru without triggering more

      loops = 2

      loop(500)
      Await.ready(latch, TestLatch.DefaultTimeout)
      count.get must be(loops)

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be(2)

      // a whole bunch should max it out

      loops = 10
      loop(500)
      Await.ready(latch, TestLatch.DefaultTimeout)
      count.get must be(loops)

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be(4)

      system.stop(pool)
    }

    "grow as needed under mailbox pressure" in {
      // make sure the pool starts at the expected lower limit and grows to the upper as needed
      // as influenced by the backlog of messages in the delegate mailboxes

      var latch = TestLatch(3)
      val count = new AtomicInteger(0)

      val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                count.incrementAndGet
                latch.countDown()
            }
          }))

          def lowerBound = 2
          def upperBound = 4
          def pressureThreshold = 3
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def receive = _route
        }).withFaultHandler(faultHandler))

      var loops = 0
      def loop(t: Int) = {
        latch = TestLatch(loops)
        count.set(0)
        for (m ← 0 until loops) {
          pool ! t
        }
      }

      // send a few messages and observe pool at its lower bound
      loops = 3
      loop(500)
      Await.ready(latch, TestLatch.DefaultTimeout)
      count.get must be(loops)

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be(2)

      // send a bunch over the threshold and observe an increment
      loops = 15
      loop(500)

      Await.ready(latch, 10 seconds)
      count.get must be(loops)

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be >= (3)

      system.stop(pool)
    }

    "round robin" in {
      val latch1 = TestLatch(2)
      val delegates = new java.util.concurrent.ConcurrentHashMap[String, String]

      val pool1 = system.actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {

          def instance(p: Props): ActorRef = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.path.toString, "")
                latch1.countDown()
            }
          }))

          def limit = 1
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = true
          def receive = _route
        }).withFaultHandler(faultHandler))

      pool1 ! "a"
      pool1 ! "b"

      Await.ready(latch1, TestLatch.DefaultTimeout)
      delegates.size must be(1)

      system.stop(pool1)

      val latch2 = TestLatch(2)
      delegates.clear()

      val pool2 = system.actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.path.toString, "")
                latch2.countDown()
            }
          }))

          def limit = 2
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = false
          def receive = _route
        }).withFaultHandler(faultHandler))

      pool2 ! "a"
      pool2 ! "b"

      Await.ready(latch2, TestLatch.DefaultTimeout)
      delegates.size must be(2)

      system.stop(pool2)
    }

    "backoff" in {
      val latch = TestLatch(10)

      val pool = system.actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                latch.countDown()
            }
          }))

          def lowerBound = 1
          def upperBound = 5
          def pressureThreshold = 1
          def partialFill = true
          def selectionCount = 1
          def rampupRate = 0.1
          def backoffRate = 0.50
          def backoffThreshold = 0.50
          def receive = _route
        }).withFaultHandler(faultHandler))

      // put some pressure on the pool

      for (m ← 0 to 10) pool ! 250

      (5 millis).dilated.sleep

      val z = Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size

      z must be >= (2)

      // let it cool down

      for (m ← 0 to 3) {
        pool ! 1
        (500 millis).dilated.sleep
      }

      Await.result((pool ? ActorPool.Stat).mapTo[ActorPool.Stats], timeout.duration).size must be <= (z)

      system.stop(pool)
    }
  }
}
