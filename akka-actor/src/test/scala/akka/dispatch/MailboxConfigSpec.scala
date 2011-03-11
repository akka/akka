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

      "dequeue in one thread what was enqueued by another" in {
        implicit val within = Duration(10,TimeUnit.SECONDS)
        val config = BoundedMailbox(false, 1000, Duration(10, TimeUnit.MILLISECONDS))
        val q = factory(config)
        ensureInitialMailboxState(config, q)

        def createProducer(fromNum: Int, toNum: Int): Future[Vector[MessageInvocation]] = spawn {
          val messages = Vector() ++ (for(i <- fromNum to toNum) yield createMessageInvocation(i))
          for(i <- messages) q.enqueue(i)
          messages
        }

        val producer1 = createProducer(1, 500)
        val producer2 = createProducer(501, 1000)

        def createConsumer: Future[Vector[MessageInvocation]] = spawn {
          var r = Vector[MessageInvocation]()
          while(!producer1.isCompleted || !producer2.isCompleted || !q.isEmpty) {
            q.dequeue match {
              case null =>
              case message => r = r :+ message
            }
          }
          r
        }

        val consumer1 = createConsumer
        val consumer2 = createConsumer

        Futures.awaitAll(List(producer1, producer2, consumer1, consumer2))

        val c1 = consumer1.result.get
        val c2 = consumer2.result.get
        val p1 = producer1.result.get
        val p2 = producer2.result.get

        (p1.size + p2.size) must be === 1000
        (c1.size + c2.size) must be === 1000
        (c1 forall (!c2.contains(_))) must be (true) //No messages produced may exist in the
        (c2 forall (!c1.contains(_))) must be (true)
        (p1 forall ( m => c1.contains(m) || c2.contains(m))) must be (true)
        (p2 forall ( m => c1.contains(m) || c2.contains(m))) must be (true)
      }
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