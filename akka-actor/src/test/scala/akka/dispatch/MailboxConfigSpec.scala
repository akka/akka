package akka.dispatch
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.actor. {Actor, ActorRegistry}
import akka.actor.Actor.{actorOf}
import java.util.concurrent. {TimeUnit, CountDownLatch, BlockingQueue}
import java.util.{Queue}
import akka.util._
import akka.util.Duration._


@RunWith(classOf[JUnitRunner])
abstract class MailboxSpec extends
  WordSpec with
  MustMatchers with
  BeforeAndAfterAll with
  BeforeAndAfterEach {
    def name: String

    def factory: MailboxType => MessageQueue

    name should {
      "create a !blockDequeue && unbounded mailbox" in {
        val config = UnboundedMailbox(false)
        val q = factory(config)
        ensureInitialMailboxState(config, q)

        implicit val within = Duration(1,TimeUnit.SECONDS)

        val f = spawn {
          q.dequeue
        }

        f.await.resultOrException must be === Some(null)
      }

      "create a !blockDequeue and bounded mailbox with 10 capacity and with push timeout" in {
        val config = BoundedMailbox(false, 10, Duration(10,TimeUnit.MILLISECONDS))
        val q = factory(config)
        ensureInitialMailboxState(config, q)

        val exampleMessage = createMessageInvocation("test")

        for(i <- 1 to config.capacity) q.enqueue(exampleMessage)

        q.size must be === config.capacity
        q.isEmpty must be === false

        intercept[MessageQueueAppendFailedException] {
          q.enqueue(exampleMessage)
        }

        q.dequeue must be === exampleMessage
        q.size must be (config.capacity - 1)
        q.isEmpty must be === false
      }

      "dequeue what was enqueued properly for unbounded mailboxes" in {
        testEnqueueDequeue(UnboundedMailbox(false))
      }

      "dequeue what was enqueued properly for bounded mailboxes" in {
        testEnqueueDequeue(BoundedMailbox(false, 10000, Duration(-1, TimeUnit.MILLISECONDS)))
      }

      "dequeue what was enqueued properly for bounded mailboxes with pushTimeout" in {
        testEnqueueDequeue(BoundedMailbox(false, 10000, Duration(100, TimeUnit.MILLISECONDS)))
      }

      /** FIXME Adapt test so it works with the last dequeue

      "dequeue what was enqueued properly for unbounded mailboxes with blockDeque" in {
        testEnqueueDequeue(UnboundedMailbox(true))
      }

      "dequeue what was enqueued properly for bounded mailboxes with blockDeque" in {
        testEnqueueDequeue(BoundedMailbox(true, 1000, Duration(-1, TimeUnit.MILLISECONDS)))
      }

      "dequeue what was enqueued properly for bounded mailboxes with blockDeque and pushTimeout" in {
        testEnqueueDequeue(BoundedMailbox(true, 1000, Duration(100, TimeUnit.MILLISECONDS)))
      }*/
    }

    //CANDIDATE FOR TESTKIT
    def spawn[T <: AnyRef](fun: => T)(implicit within: Duration): Future[T] = {
      val result = new DefaultCompletableFuture[T](within.length, within.unit)
      val t = new Thread(new Runnable {
        def run = try {
          result.completeWithResult(fun)
        } catch {
          case e: Throwable => result.completeWithException(e)
        }
      })
      t.start
      result
    }

    def createMessageInvocation(msg: Any): MessageInvocation = {
      new MessageInvocation(
        actorOf(new Actor { //Dummy actor
          def receive = { case _ => }
        }), msg, None, None)
    }

    def ensureInitialMailboxState(config: MailboxType, q: MessageQueue) {
      q must not be null
      q match {
        case aQueue: BlockingQueue[_] =>
          config match {
            case BoundedMailbox(_,capacity,_) => aQueue.remainingCapacity must be === capacity
            case UnboundedMailbox(_) => aQueue.remainingCapacity must be === Int.MaxValue
          }
        case _ =>
      }
      q.size must be === 0
      q.isEmpty must be === true
    }

    def testEnqueueDequeue(config: MailboxType) {
      implicit val within = Duration(10,TimeUnit.SECONDS)
        val q = factory(config)
        ensureInitialMailboxState(config, q)

        def createProducer(fromNum: Int, toNum: Int): Future[Vector[MessageInvocation]] = spawn {
          val messages = Vector() ++ (for(i <- fromNum to toNum) yield createMessageInvocation(i))
          for(i <- messages) q.enqueue(i)
          messages
        }

        val totalMessages = 10000
        val step = 500

        val producers = for(i <- (1 to totalMessages by step).toList) yield createProducer(i,i+step-1)

        def createConsumer: Future[Vector[MessageInvocation]] = spawn {
          var r = Vector[MessageInvocation]()
          while(producers.exists(_.isCompleted == false) || !q.isEmpty) {
            q.dequeue match {
              case null =>
              case message => r = r :+ message
            }
          }
          r
        }

        val consumers = for(i <- (1 to 4).toList) yield createConsumer

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
    case UnboundedMailbox(blockDequeue) =>
      new DefaultUnboundedMessageQueue(blockDequeue)
    case BoundedMailbox(blocking, capacity, pushTimeOut) =>
      new DefaultBoundedMessageQueue(capacity, pushTimeOut, blocking)
  }
}

class PriorityMailboxSpec extends MailboxSpec {
  val comparator = new java.util.Comparator[MessageInvocation] {
    def compare(a: MessageInvocation, b: MessageInvocation): Int  = {
      a.## - b.##
    }
  }
  lazy val name = "The priority mailbox implementation"
  def factory = {
    case UnboundedMailbox(blockDequeue) =>
      new UnboundedPriorityMessageQueue(blockDequeue, comparator)
    case BoundedMailbox(blocking, capacity, pushTimeOut) =>
      new BoundedPriorityMessageQueue(capacity, pushTimeOut, blocking, comparator)
  }
}