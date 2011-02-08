package akka.actor.routing

import akka.actor.Actor
import akka.actor.Actor._
import akka.util.Logging

import org.scalatest.Suite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.Test

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.routing._

@RunWith(classOf[JUnitRunner])
class RoutingSpec extends junit.framework.TestCase with Suite with MustMatchers with Logging {
  import Routing._

  @Test def testDispatcher = {
    val (testMsg1,testMsg2,testMsg3,testMsg4) = ("test1","test2","test3","test4")
    val targetOk = new AtomicInteger(0)
    val t1 = actorOf( new Actor() {
          def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t2 = actorOf( new Actor() {
          def receive = {
        case `testMsg3` => self.reply(11)
      }
    }).start

    val d = dispatcherActor {
      case `testMsg1`|`testMsg2` => t1
      case `testMsg3` => t2
    }.start

    val result = for {
      a <- (d !! (testMsg1, 5000)).as[Int]
      b <- (d !! (testMsg2, 5000)).as[Int]
      c <- (d !! (testMsg3, 5000)).as[Int]
    } yield a + b + c

    result.isDefined must be (true)
    result.get must be(21)

    for(a <- List(t1,t2,d)) a.stop
  }

  @Test def testLogger = {
    val msgs = new java.util.concurrent.ConcurrentSkipListSet[Any]
    val latch = new CountDownLatch(2)
    val t1 = actorOf(new Actor { def receive = { case _ => } }).start
    val l = loggerActor(t1,(x) => { msgs.add(x); latch.countDown }).start
    val foo : Any = "foo"
    val bar : Any = "bar"
    l ! foo
    l ! bar
    val done = latch.await(5,TimeUnit.SECONDS)
    done must be (true)
    msgs must ( have size (2) and contain (foo) and contain (bar) )
    t1.stop
    l.stop
  }

  @Test def testSmallestMailboxFirstDispatcher = {
    val t1ProcessedCount = new AtomicInteger(0)
    val latch = new CountDownLatch(500)
    val t1 = actorOf(new Actor {
      def receive = {
        case x =>
          Thread.sleep(50) // slow actor
          t1ProcessedCount.incrementAndGet
          latch.countDown
      }
    }).start

    val t2ProcessedCount = new AtomicInteger(0)
    val t2 = actorOf(new Actor {
      def receive = {
        case x => t2ProcessedCount.incrementAndGet
                  latch.countDown
      }
    }).start
    val d = loadBalancerActor(new SmallestMailboxFirstIterator(t1 :: t2 :: Nil))
    for (i <- 1 to 500) d ! i
    val done = latch.await(10,TimeUnit.SECONDS)
    done must be (true)
    t1ProcessedCount.get must be < (t2ProcessedCount.get) // because t1 is much slower and thus has a bigger mailbox all the time
    for(a <- List(t1,t2,d)) a.stop
  }

  @Test def testListener = {
    val latch = new CountDownLatch(2)
    val foreachListener = new CountDownLatch(2)
    val num = new AtomicInteger(0)
    val i = actorOf(new Actor with Listeners {
      def receive = listenerManagement orElse {
        case "foo" =>  gossip("bar")
      }
    })
    i.start

    def newListener = actorOf(new Actor {
      def receive = {
        case "bar" =>
          num.incrementAndGet
          latch.countDown
        case "foo" => foreachListener.countDown
      }
    }).start

    val a1 = newListener
    val a2 = newListener
    val a3 = newListener

    i ! Listen(a1)
    i ! Listen(a2)
    i ! Listen(a3)
    i ! Deafen(a3)
    i ! WithListeners(_ ! "foo")
    i ! "foo"

    val done = latch.await(5,TimeUnit.SECONDS)
    done must be (true)
    num.get must be (2)
    val withListeners = foreachListener.await(5,TimeUnit.SECONDS)
    withListeners must be (true)
    for(a <- List(i,a1,a2,a3)) a.stop
  }

  @Test def testIsDefinedAt = {
        import akka.actor.ActorRef

        val (testMsg1,testMsg2,testMsg3,testMsg4) = ("test1","test2","test3","test4")

    val t1 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t2 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t3 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t4 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val d1 = loadBalancerActor(new SmallestMailboxFirstIterator(t1 :: t2 :: Nil))
    val d2 = loadBalancerActor(new CyclicIterator[ActorRef](t3 :: t4 :: Nil))

    t1.isDefinedAt(testMsg1) must be (true)
    t1.isDefinedAt(testMsg3) must be (false)
    t2.isDefinedAt(testMsg1) must be (true)
    t2.isDefinedAt(testMsg3) must be (false)
    d1.isDefinedAt(testMsg1) must be (true)
    d1.isDefinedAt(testMsg3) must be (false)
    d2.isDefinedAt(testMsg1) must be (true)
    d2.isDefinedAt(testMsg3) must be (false)

    for(a <- List(t1,t2,d1,d2)) a.stop
  }

	// Actor Pool Capacity Tests
	
		//
		// make sure the pool is of the fixed, expected capacity
		//
  	@Test def testFixedCapacityActorPool = {

		val latch = new CountDownLatch(2)
		val counter = new AtomicInteger(0)
		class TestPool extends Actor with DefaultActorPool 
									 with FixedCapacityStrategy
									 with SmallestMailboxSelector
		{
			def factory = actorOf(new Actor {
				def receive = {
					case _ =>
						counter.incrementAndGet
						latch.countDown
				}
			})
			
			def limit = 2
			def selectionCount = 1
			def partialFill = true
			def instance = factory
			def receive = _route
		}
		
		val pool = actorOf(new TestPool).start
		pool ! "a"
		pool ! "b"
		val done = latch.await(1,TimeUnit.SECONDS)
		done must be (true)
		counter.get must be (2)
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be (2)
		
		pool stop
	}
	
		//
		// make sure the pool starts at the expected lower limit and grows to the upper as needed
		//	as influenced by the backlog of blocking pooled actors
		//
  	@Test def testBoundedCapacityActorPoolWithActiveFuturesPressure = {

		var latch = new CountDownLatch(3)
		val counter = new AtomicInteger(0)
		class TestPool extends Actor with DefaultActorPool 
									 with BoundedCapacityStrategy
									 with ActiveFuturesPressureCapacitor
									 with SmallestMailboxSelector
		{
			def factory = actorOf(new Actor {
				def receive = {
					case n:Int => 
						Thread.sleep(n)
						counter.incrementAndGet
						latch.countDown
				}
			})

			def lowerBound = 2
			def upperBound = 4
			def capacityIncrement = 1
			def partialFill = true
			def selectionCount = 1
			def instance = factory
			def receive = _route
		}

		//
		// first message should create the minimum number of delgates
		//
		val pool = actorOf(new TestPool).start
		pool ! 1
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be (2)

		var loops = 0
		def loop(t:Int) = {
			latch = new CountDownLatch(loops)
			counter.set(0)
			for (m <- 0 until loops) {
				pool !!! t
				Thread.sleep(50)
			}
		}
		
		//
		// 2 more should go thru w/out triggering more
		//
		loops = 2
		loop(500)
		var done = latch.await(5,TimeUnit.SECONDS)
		done must be (true)
		counter.get must be (loops)
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be (2)

		//
		// a whole bunch should max it out
		//
		loops = 10
		loop(500)

		done = latch.await(5,TimeUnit.SECONDS)
		done must be (true)
		counter.get must be (loops)
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be (4)
		
		pool stop
	}

		//
		// make sure the pool starts at the expected lower limit and grows to the upper as needed
		//	as influenced by the backlog of messages in the delegate mailboxes
		//
  	@Test def testBoundedCapacityActorPoolWithMailboxPressure = {

		var latch = new CountDownLatch(3)
		val counter = new AtomicInteger(0)
		class TestPool extends Actor with DefaultActorPool 
									 with BoundedCapacityStrategy
									 with MailboxPressureCapacitor
									 with SmallestMailboxSelector
		{
			def factory = actorOf(new Actor {
				def receive = {
					case n:Int => 
						Thread.sleep(n)
						counter.incrementAndGet
						latch.countDown
				}
			})

			def lowerBound = 2
			def upperBound = 4
			def pressureThreshold = 3
			def capacityIncrement = 1
			def partialFill = true
			def selectionCount = 1
			def instance = factory
			def receive = _route
		}

		val pool = actorOf(new TestPool).start

		var loops = 0
		def loop(t:Int) = {
			latch = new CountDownLatch(loops)
			counter.set(0)
			for (m <- 0 until loops) {
				pool ! t
			}
		}
		
		//
		// send a few messages and observe pool at its lower bound
		//
		loops = 3
		loop(500)
		var done = latch.await(5,TimeUnit.SECONDS)
		done must be (true)
		counter.get must be (loops)
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be (2)

		//
		// send a bunch over the theshold and observe an increment
		//
		loops = 15
		loop(500)

		done = latch.await(10,TimeUnit.SECONDS)
		done must be (true)
		counter.get must be (loops)
		(pool !! ActorPool.Stat).asInstanceOf[Option[ActorPool.Stats]].get.size must be >= (3)
		
		pool stop
	}
	
	// Actor Pool Selector Tests
	
	@Test def testRoundRobinSelector = {

		var latch = new CountDownLatch(2)
		val delegates = new java.util.concurrent.ConcurrentHashMap[String, String]
		
		class TestPool1 extends Actor with DefaultActorPool 
									  with FixedCapacityStrategy
									  with RoundRobinSelector
		{
			def factory = actorOf(new Actor {
				def receive = {
					case _ => 
						delegates put(self.uuid.toString, "")
						latch.countDown
				}
			})
			
			def limit = 1
			def selectionCount = 2
			def partialFill = true
			def instance = factory
			def receive = _route
		}
		
		val pool1 = actorOf(new TestPool1).start
		pool1 ! "a"
		pool1 ! "b"
		var done = latch.await(1,TimeUnit.SECONDS)
		done must be (true)
		delegates.size must be (1)
		pool1 stop
		
		class TestPool2 extends Actor with DefaultActorPool 
					 				  with FixedCapacityStrategy
					 				  with RoundRobinSelector
		{
			def factory = actorOf(new Actor {
				def receive = {
					case _ => 
						delegates put(self.uuid.toString, "")
						latch.countDown
				}
			})
			
			def limit = 2
			def selectionCount = 2
			def partialFill = false
			def instance = factory
			def receive = _route
		}		

		latch = new CountDownLatch(2)
		delegates clear
		
		val pool2 = actorOf(new TestPool2).start
		pool2 ! "a"
		pool2 ! "b"
	 	done = latch.await(1,TimeUnit.SECONDS)
		done must be (true)
		delegates.size must be (2)
		pool2 stop
	}
}
