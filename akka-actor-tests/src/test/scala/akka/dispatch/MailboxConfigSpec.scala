package akka.dispatch

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import java.util.concurrent.{ TimeUnit, BlockingQueue }
import java.util.concurrent.ConcurrentLinkedQueue
import akka.util._
import akka.util.duration._
import akka.testkit.AkkaSpec
import akka.actor.ActorRef
import akka.actor.ActorContext
import com.typesafe.config.Config

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
abstract class MailboxSpec extends AkkaSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  def name: String

  def factory: MailboxType ⇒ Mailbox

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

      intercept[MessageQueueAppendFailedException] {
        q.enqueue(null, exampleMessage)
      }

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

  def createMessageInvocation(msg: Any): Envelope = Envelope(msg, system.deadLetters)(system)

  def ensureInitialMailboxState(config: MailboxType, q: Mailbox) {
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
    case u: UnboundedMailbox ⇒ u.create(null)
    case b: BoundedMailbox   ⇒ b.create(null)
  }
}

class PriorityMailboxSpec extends MailboxSpec {
  val comparator = PriorityGenerator(_.##)
  lazy val name = "The priority mailbox implementation"
  def factory = {
    case UnboundedMailbox()                    ⇒ UnboundedPriorityMailbox(comparator).create(null)
    case BoundedMailbox(capacity, pushTimeOut) ⇒ BoundedPriorityMailbox(comparator, capacity, pushTimeOut).create(null)
  }
}

object CustomMailboxSpec {
  val config = """
    my-dispatcher {
       mailbox-type = "akka.dispatch.CustomMailboxSpec$MyMailboxType"
    }
    """

  class MyMailboxType(config: Config) extends MailboxType {
    override def create(owner: ActorContext) = new MyMailbox(owner)
  }

  class MyMailbox(owner: ActorContext) extends CustomMailbox(owner)
    with QueueBasedMessageQueue with UnboundedMessageQueueSemantics with DefaultSystemMessageQueue {
    final val queue = new ConcurrentLinkedQueue[Envelope]()
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class CustomMailboxSpec extends AkkaSpec(CustomMailboxSpec.config) {
  "Dispatcher configuration" must {
    "support custom mailboxType" in {
      val dispatcher = system.dispatchers.lookup("my-dispatcher")
      dispatcher.createMailbox(null).getClass must be(classOf[CustomMailboxSpec.MyMailbox])
    }
  }
}
