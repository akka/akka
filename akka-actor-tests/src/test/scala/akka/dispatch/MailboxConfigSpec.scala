/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch

import language.postfixOps

import java.util.concurrent.{ ConcurrentLinkedQueue, BlockingQueue }
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import com.typesafe.config.Config
import akka.actor.{ RepointableRef, Props, DeadLetter, ActorSystem, ActorRefWithCell, ActorRef, ActorCell }
import akka.testkit.AkkaSpec
import akka.util.duration.intToDurationInt

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class MailboxSpec extends AkkaSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  def name: String

  def factory: MailboxType ⇒ MessageQueue

  name should {
    "create an unbounded mailbox" in {
      val config = UnboundedMailbox()
      val q = factory(config)
      ensureInitialMailboxState(config, q)

      val f = spawn { q.dequeue }

      Await.result(f, 1 second) must be(null)
    }

    "create a bounded mailbox with 10 capacity and with push timeout" in {
      val config = BoundedMailbox(10, 10 milliseconds)
      val q = factory(config)
      ensureInitialMailboxState(config, q)

      val exampleMessage = createMessageInvocation("test")

      for (i ← 1 to config.capacity) q.enqueue(null, exampleMessage)

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

    "dequeue what was enqueued properly for bounded mailboxes with pushTimeout" in {
      testEnqueueDequeue(BoundedMailbox(10000, 100 milliseconds))
    }
  }

  //CANDIDATE FOR TESTKIT
  def spawn[T <: AnyRef](fun: ⇒ T): Future[T] = {
    val result = Promise[T]()
    val t = new Thread(new Runnable {
      def run = try {
        result.success(fun)
      } catch {
        case e: Throwable ⇒ result.failure(e)
      }
    })
    t.start
    result
  }

  def createMessageInvocation(msg: Any): Envelope = Envelope(msg, system.deadLetters, system)

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

  def testEnqueueDequeue(config: MailboxType) {
    implicit val within = 10 seconds
    val q = factory(config)
    ensureInitialMailboxState(config, q)

    def createProducer(fromNum: Int, toNum: Int): Future[Vector[Envelope]] = spawn {
      val messages = Vector() ++ (for (i ← fromNum to toNum) yield createMessageInvocation(i))
      for (i ← messages) q.enqueue(null, i)
      messages
    }

    val totalMessages = 10000
    val step = 500

    val producers = for (i ← (1 to totalMessages by step).toList) yield createProducer(i, i + step - 1)

    def createConsumer: Future[Vector[Envelope]] = spawn {
      var r = Vector[Envelope]()
      while (producers.exists(_.isCompleted == false) || q.hasMessages) {
        q.dequeue match {
          case null    ⇒
          case message ⇒ r = r :+ message
        }
      }
      r
    }

    val consumers = for (i ← (1 to 4).toList) yield createConsumer

    val ps = producers.map(Await.result(_, within))
    val cs = consumers.map(Await.result(_, within))

    ps.map(_.size).sum must be === totalMessages //Must have produced 1000 messages
    cs.map(_.size).sum must be === totalMessages //Must have consumed all produced messages
    //No message is allowed to be consumed by more than one consumer
    cs.flatten.distinct.size must be === totalMessages
    //All produced messages should have been consumed
    (cs.flatten diff ps.flatten).size must be === 0
    (ps.flatten diff cs.flatten).size must be === 0
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

  class MyMailbox(owner: ActorRef) extends QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
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
