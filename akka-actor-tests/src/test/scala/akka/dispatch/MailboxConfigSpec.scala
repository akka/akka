/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import language.postfixOps

import java.util.concurrent.{ ConcurrentLinkedQueue, BlockingQueue }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor._
import akka.testkit.{ EventFilter, AkkaSpec }
import scala.concurrent.{ Future, Await, ExecutionContext }
import scala.concurrent.duration._
import akka.dispatch.{ UnboundedMailbox, BoundedMailbox, SingleConsumerOnlyUnboundedMailbox }

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class MailboxSpec extends AkkaSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  def name: String

  def factory: MailboxType ⇒ MessageQueue

  def supportsBeingBounded = true

  def maxConsumers = 4

  private val exampleMessage = createMessageInvocation("test")

  name should {

    "create an unbounded mailbox" in {
      val config = UnboundedMailbox()
      val q = factory(config)
      ensureInitialMailboxState(config, q)
    }

    "UnboundedMailbox.numberOfMessages must be consistent with queue size" in {
      ensureSingleConsumerEnqueueDequeue(UnboundedMailbox())
    }

    "BoundedMailbox.numberOfMessages must be consistent with queue size" in {
      ensureSingleConsumerEnqueueDequeue(BoundedMailbox(1000, 10 milliseconds))
    }

    "create a bounded mailbox with 10 capacity and with push timeout" in {
      val config = BoundedMailbox(10, 10 milliseconds)
      val q = factory(config)
      ensureInitialMailboxState(config, q)

      for (i ← 1 to config.capacity) q.enqueue(testActor, exampleMessage)

      q.numberOfMessages must be === config.capacity
      q.hasMessages must be === true

      system.eventStream.subscribe(testActor, classOf[DeadLetter])
      q.enqueue(testActor, exampleMessage)
      expectMsg(DeadLetter(exampleMessage.message, system.deadLetters, testActor))
      system.eventStream.unsubscribe(testActor, classOf[DeadLetter])

      q.dequeue must be === exampleMessage
      q.numberOfMessages must be(config.capacity - 1)
      q.hasMessages must be === true
    }

    "dequeue what was enqueued properly for unbounded mailboxes" in {
      testEnqueueDequeue(UnboundedMailbox())
    }

    "dequeue what was enqueued properly for bounded mailboxes" in {
      testEnqueueDequeue(BoundedMailbox(10000, -1 millisecond))
    }

    "dequeue what was enqueued properly for bounded mailboxes with 0 pushTimeout" in {
      testEnqueueDequeue(BoundedMailbox(10, 0 millisecond), 20, 10, false)
    }

    "dequeue what was enqueued properly for bounded mailboxes with pushTimeout" in {
      testEnqueueDequeue(BoundedMailbox(10000, 100 milliseconds))
    }
  }

  //CANDIDATE FOR TESTKIT
  def spawn[T <: AnyRef](fun: ⇒ T): Future[T] = Future(fun)(ExecutionContext.global)

  def createMessageInvocation(msg: Any): Envelope = Envelope(msg, system.deadLetters, system)

  def ensureMailboxSize(q: MessageQueue, expected: Int): Unit = q.numberOfMessages match {
    case -1 | `expected` ⇒
      q.hasMessages must be === (expected != 0)
    case other ⇒
      other must be === expected
      q.hasMessages must be === (expected != 0)
  }

  def ensureSingleConsumerEnqueueDequeue(config: MailboxType) {
    val q = factory(config)
    ensureMailboxSize(q, 0)
    q.dequeue must be === null
    for (i ← 1 to 100) {
      q.enqueue(testActor, exampleMessage)
      ensureMailboxSize(q, i)
    }

    ensureMailboxSize(q, 100)

    for (i ← 99 to 0 by -1) {
      q.dequeue() must be === exampleMessage
      ensureMailboxSize(q, i)
    }

    q.dequeue must be === null
    ensureMailboxSize(q, 0)
  }

  def ensureInitialMailboxState(config: MailboxType, q: MessageQueue) {
    q must not be null
    q match {
      case aQueue: BlockingQueue[_] ⇒
        config match {
          case BoundedMailbox(capacity, _) ⇒ aQueue.remainingCapacity must be === capacity
          case UnboundedMailbox()          ⇒ aQueue.remainingCapacity must be === Int.MaxValue
        }
      case _ ⇒
    }
    q.numberOfMessages must be === 0
    q.hasMessages must be === false
  }

  def testEnqueueDequeue(config: MailboxType,
                         enqueueN: Int = 10000,
                         dequeueN: Int = 10000,
                         parallel: Boolean = true): Unit = within(10 seconds) {
    val q = factory(config)
    ensureInitialMailboxState(config, q)

    EventFilter.warning(pattern = ".*received dead letter from Actor.*MailboxSpec/deadLetters.*",
      occurrences = (enqueueN - dequeueN)) intercept {

        def createProducer(fromNum: Int, toNum: Int): Future[Vector[Envelope]] = spawn {
          val messages = Vector() ++ (for (i ← fromNum to toNum) yield createMessageInvocation(i))
          for (i ← messages) q.enqueue(testActor, i)
          messages
        }

        val producers = {
          val step = 500
          val ps = for (i ← (1 to enqueueN by step).toList) yield createProducer(i, Math.min(enqueueN, i + step - 1))

          if (parallel == false)
            ps foreach { Await.ready(_, remaining) }

          ps
        }

        def createConsumer: Future[Vector[Envelope]] = spawn {
          var r = Vector[Envelope]()

          while (producers.exists(_.isCompleted == false) || q.hasMessages)
            Option(q.dequeue) foreach { message ⇒ r = r :+ message }

          r
        }

        val consumers = List.fill(maxConsumers)(createConsumer)

        val ps = producers.map(Await.result(_, remaining))
        val cs = consumers.map(Await.result(_, remaining))

        ps.map(_.size).sum must be === enqueueN //Must have produced 1000 messages
        cs.map(_.size).sum must be === dequeueN //Must have consumed all produced messages
        //No message is allowed to be consumed by more than one consumer
        cs.flatten.distinct.size must be === dequeueN
        //All consumed messages must have been produced
        (cs.flatten diff ps.flatten).size must be === 0
        //The ones that were produced and not consumed
        (ps.flatten diff cs.flatten).size must be === (enqueueN - dequeueN)
      }
  }
}

class DefaultMailboxSpec extends MailboxSpec {
  lazy val name = "The default mailbox implementation"
  def factory = {
    case u: UnboundedMailbox ⇒ u.create(None, None)
    case b: BoundedMailbox   ⇒ b.create(None, None)
  }
}

class PriorityMailboxSpec extends MailboxSpec {
  val comparator = PriorityGenerator(_.##)
  lazy val name = "The priority mailbox implementation"
  def factory = {
    case UnboundedMailbox()                    ⇒ new UnboundedPriorityMailbox(comparator).create(None, None)
    case BoundedMailbox(capacity, pushTimeOut) ⇒ new BoundedPriorityMailbox(comparator, capacity, pushTimeOut).create(None, None)
  }
}

object CustomMailboxSpec {
  val config = """
    my-dispatcher {
       mailbox-type = "akka.dispatch.CustomMailboxSpec$MyMailboxType"
    }
    """

  class MyMailboxType(settings: ActorSystem.Settings, config: Config) extends MailboxType {
    override def create(owner: Option[ActorRef], system: Option[ActorSystem]) = owner match {
      case Some(o) ⇒ new MyMailbox(o)
      case None    ⇒ throw new Exception("no mailbox owner given")
    }
  }

  class MyMailbox(owner: ActorRef) extends UnboundedQueueBasedMessageQueue {
    final val queue = new ConcurrentLinkedQueue[Envelope]()
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CustomMailboxSpec extends AkkaSpec(CustomMailboxSpec.config) {
  "Dispatcher configuration" must {
    "support custom mailboxType" in {
      val actor = system.actorOf(Props.empty.withDispatcher("my-dispatcher"))
      awaitCond(actor match {
        case r: RepointableRef ⇒ r.isStarted
        case _                 ⇒ true
      }, 1 second, 10 millis)
      val queue = actor.asInstanceOf[ActorRefWithCell].underlying.asInstanceOf[ActorCell].mailbox.messageQueue
      queue.getClass must be(classOf[CustomMailboxSpec.MyMailbox])
    }
  }
}

class SingleConsumerOnlyMailboxSpec extends MailboxSpec {
  lazy val name = "The single-consumer-only mailbox implementation"
  override def maxConsumers = 1
  def factory = {
    case u: UnboundedMailbox ⇒ SingleConsumerOnlyUnboundedMailbox().create(None, None)
    case b: BoundedMailbox   ⇒ pending; null
  }
}

object SingleConsumerOnlyMailboxVerificationSpec {
  case object Ping
  val mailboxConf = ConfigFactory.parseString("""
      akka.actor.serialize-messages = off
      test-dispatcher {
      mailbox-type = "akka.dispatch.SingleConsumerOnlyUnboundedMailbox"
      throughput = 1
      }""")
}

class SingleConsumerOnlyMailboxVerificationSpec extends AkkaSpec(SingleConsumerOnlyMailboxVerificationSpec.mailboxConf) {
  import SingleConsumerOnlyMailboxVerificationSpec.Ping
  "A SingleConsumerOnlyMailbox" should {
    "support pathological ping-ponging" in within(30.seconds) {
      val total = 2000000
      val runner = system.actorOf(Props(new Actor {
        val a, b = context.watch(
          context.actorOf(Props(new Actor {
            var n = total / 2
            def receive = {
              case Ping ⇒
                n -= 1
                sender ! Ping
                if (n == 0)
                  context stop self
            }
          }).withDispatcher("test-dispatcher")))
        def receive = {
          case Ping                  ⇒ a.tell(Ping, b)
          case Terminated(`a` | `b`) ⇒ if (context.children.isEmpty) context stop self
        }
      }))
      watch(runner)
      runner ! Ping
      expectTerminated(runner)
    }
  }
}
