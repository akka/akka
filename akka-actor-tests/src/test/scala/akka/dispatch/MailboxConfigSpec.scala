package akka.dispatch
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import java.util.concurrent.{ TimeUnit, CountDownLatch, BlockingQueue }
import java.util.{ Queue }
import akka.util._
import akka.util.Duration._
import akka.actor.{ LocalActorRef, Actor, NullChannel }
import akka.testkit.AkkaSpec

@RunWith(classOf[JUnitRunner])
abstract class MailboxSpec extends AkkaSpec with BeforeAndAfterAll with BeforeAndAfterEach {
  def name: String

  def factory: MailboxType ⇒ Mailbox

  name should {
    "create an unbounded mailbox" in {
      val config = UnboundedMailbox()
      val q = factory(config)
      ensureInitialMailboxState(config, q)

      implicit val within = Duration(1, TimeUnit.SECONDS)

      val f = spawn {
        q.dequeue
      }

      f.await.resultOrException must be === Some(null)
    }

    "create a bounded mailbox with 10 capacity and with push timeout" in {
      val config = BoundedMailbox(10, Duration(10, TimeUnit.MILLISECONDS))
      val q = factory(config)
      ensureInitialMailboxState(config, q)

      val exampleMessage = createMessageInvocation("test")

      for (i ← 1 to config.capacity) q.enqueue(exampleMessage)

      q.numberOfMessages must be === config.capacity
      q.hasMessages must be === true

      intercept[MessageQueueAppendFailedException] {
        q.enqueue(exampleMessage)
      }

      q.dequeue must be === exampleMessage
      q.numberOfMessages must be(config.capacity - 1)
      q.hasMessages must be === true
    }

    "dequeue what was enqueued properly for unbounded mailboxes" in {
      testEnqueueDequeue(UnboundedMailbox())
    }

    "dequeue what was enqueued properly for bounded mailboxes" in {
      testEnqueueDequeue(BoundedMailbox(10000, Duration(-1, TimeUnit.MILLISECONDS)))
    }

    "dequeue what was enqueued properly for bounded mailboxes with pushTimeout" in {
      testEnqueueDequeue(BoundedMailbox(10000, Duration(100, TimeUnit.MILLISECONDS)))
    }
  }

  //CANDIDATE FOR TESTKIT
  def spawn[T <: AnyRef](fun: ⇒ T)(implicit within: Duration): Future[T] = {
    val result = new DefaultPromise[T](within.length, within.unit)
    val t = new Thread(new Runnable {
      def run = try {
        result.completeWithResult(fun)
      } catch {
        case e: Throwable ⇒ result.completeWithException(e)
      }
    })
    t.start
    result
  }

  def createMessageInvocation(msg: Any): Envelope = {
    new Envelope(
      createActor(new Actor { //Dummy actor
        def receive = { case _ ⇒ }
      }).asInstanceOf[LocalActorRef].underlying, msg, NullChannel)
  }

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
    implicit val within = Duration(10, TimeUnit.SECONDS)
    val q = factory(config)
    ensureInitialMailboxState(config, q)

    def createProducer(fromNum: Int, toNum: Int): Future[Vector[Envelope]] = spawn {
      val messages = Vector() ++ (for (i ← fromNum to toNum) yield createMessageInvocation(i))
      for (i ← messages) q.enqueue(i)
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

    val ps = producers.map(_.await.resultOrException.get)
    val cs = consumers.map(_.await.resultOrException.get)

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
