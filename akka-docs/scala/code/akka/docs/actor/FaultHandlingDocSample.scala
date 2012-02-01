/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

//#all
import akka.actor._
import akka.actor.SupervisorStrategy._
import scala.util.duration._
import scala.util.Duration
import scala.util.Timeout
import akka.event.LoggingReceive
import akka.pattern.ask
import com.typesafe.config.ConfigFactory

/**
 * Runs the sample
 */
object FaultHandlingDocSample extends App {
  import Worker._
  import CounterService._

  val config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)

  val system = ActorSystem("FaultToleranceSample", config)
  val worker = system.actorOf(Props[Worker], name = "worker")

  // Create an Actor that start the work and listens to progress
  system.actorOf(Props(new Actor with ActorLogging {
    // If we don't get any progress within 15 seconds then the service is unavailable
    context.setReceiveTimeout(15 seconds)
    worker ! Start

    def receive = {
      case CurrentCount(key, count) ⇒
        log.info("Current count for [{}] is [{}]", key, count)
        if (count > 50) {
          log.info("That's enough, shutting down")
          system.shutdown()
        }

      case ReceiveTimeout ⇒
        // No progress within 15 seconds, ServiceUnavailable
        log.error("Shutting down due to unavailable service")
        system.shutdown()
    }
  }))

}

object Worker {
  // Messages
  case object Start
  case object Do
}

/**
 * Worker performs some work when it receives the `Start` message.
 * It will continuously notify the sender of the `Start` message
 * of current progress. The `Worker` supervise the `CounterService`.
 */
class Worker extends Actor with ActorLogging {
  import Worker._
  import CounterService._
  implicit def system = context.system
  implicit val askTimeout = Timeout(5 seconds)

  // Stop the CounterService child if it throws ServiceUnavailable
  override val supervisorStrategy = OneForOneStrategy() {
    case _: CounterService.ServiceUnavailable ⇒ Stop
  }

  // The sender of the initial Start message will continuously be notified about progress
  var progressListener: Option[ActorRef] = None
  val counterService = context.actorOf(Props[CounterService], name = "counter")

  def receive = LoggingReceive(this) {
    case Start if progressListener.isEmpty ⇒
      progressListener = Some(sender)
      context.system.scheduler.schedule(Duration.Zero, 1 second, self, Do)

    case Do ⇒
      counterService ! Increment(1)
      counterService ! Increment(1)
      counterService ! Increment(1)

      // Send current count to the initial sender
      counterService ? GetCurrentCount pipeTo progressListener.get
  }
}

object CounterService {
  // Messages
  case class Increment(n: Int)
  case object GetCurrentCount
  case class CurrentCount(key: String, count: Long)
  case object Reconnect
  class ServiceUnavailable(msg: String) extends RuntimeException(msg)
}

/**
 * Adds the value received in `Increment` message to a persistent
 * counter. Replies with `CurrentCount` when it is asked for `CurrentCount`.
 * `CounterService` supervise `Storage` and `Counter`.
 */
class CounterService extends Actor {
  import CounterService._
  import Counter._
  import Storage._
  implicit def system = context.system

  // Restart the storage child when StorageException is thrown.
  // After 3 restarts within 5 seconds it will be stopped.
  override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 5 seconds) {
    case _: Storage.StorageException ⇒ Restart
  }

  val key = context.self.path.name
  var storage: Option[ActorRef] = None
  var counter: Option[ActorRef] = None
  var backlog = IndexedSeq.empty[Any]
  val MaxBacklog = 10000

  override def preStart() {
    initStorage()
  }

  /**
   * The child storage is restarted in case of failure, but after 3 restarts,
   * and still failing it will be stopped. Better to back-off than continuously
   * failing. When it has been stopped we will schedule a Reconnect after a delay.
   * Watch the child so we receive Terminated message when it has been terminated.
   */
  def initStorage() {
    storage = Some(context.watch(context.actorOf(Props[Storage], name = "storage")))
    // Tell the counter, if any, to use the new storage
    counter foreach { _ ! UseStorage(storage) }
    // We need the initial value to be able to operate
    storage.get ! Get(key)
  }

  def receive = LoggingReceive(this) {

    case Entry(k, v) if k == key && counter == None ⇒
      // Reply from Storage of the initial value, now we can create the Counter
      val c = context.actorOf(Props(new Counter(key, v)))
      counter = Some(c)
      // Tell the counter to use current storage
      c ! UseStorage(storage)
      // and send the buffered backlog to the counter
      backlog foreach { c ! _ }
      backlog = IndexedSeq.empty

    case msg @ Increment(n)    ⇒ forwardOrPlaceInBacklog(msg)

    case msg @ GetCurrentCount ⇒ forwardOrPlaceInBacklog(msg)

    case Terminated(actorRef) if Some(actorRef) == storage ⇒
      // After 3 restarts the storage child is stopped.
      // We receive Terminated because we watch the child, see initStorage.
      storage = None
      // Tell the counter that there is no storage for the moment
      counter foreach { _ ! UseStorage(None) }
      // Try to re-establish storage after while
      context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)

    case Reconnect ⇒
      // Re-establish storage after the scheduled delay
      initStorage()
  }

  def forwardOrPlaceInBacklog(msg: Any) {
    // We need the initial value from storage before we can start delegate to the counter.
    // Before that we place the messages in a backlog, to be sent to the counter when
    // it is initialized.
    counter match {
      case Some(c) ⇒ c forward msg
      case None ⇒
        if (backlog.size >= MaxBacklog)
          throw new ServiceUnavailable("CounterService not available, lack of initial value")
        backlog = backlog :+ msg
    }
  }

}

object Counter {
  // Messages
  case class UseStorage(storage: Option[ActorRef])
}

/**
 * The in memory count variable that will send current
 * value to the `Storage`, if there is any storage
 * available at the moment.
 */
class Counter(key: String, initialValue: Long) extends Actor {
  import Counter._
  import CounterService._
  import Storage._
  implicit def system = context.system

  var count = initialValue
  var storage: Option[ActorRef] = None

  def receive = LoggingReceive(this) {
    case UseStorage(s) ⇒
      storage = s
      storeCount()

    case Increment(n) ⇒
      count += n
      storeCount()

    case GetCurrentCount ⇒
      sender ! CurrentCount(key, count)

  }

  def storeCount() {
    // Delegate dangerous work, to protect our valuable state.
    // We can continue without storage.
    storage foreach { _ ! Store(Entry(key, count)) }
  }

}

object Storage {
  // Messages
  case class Store(entry: Entry)
  case class Get(key: String)
  case class Entry(key: String, value: Long)
  class StorageException(msg: String) extends RuntimeException(msg)
}

/**
 * Saves key/value pairs to persistent storage when receiving `Store` message.
 * Replies with current value when receiving `Get` message.
 * Will throw StorageException if the underlying data store is out of order.
 */
class Storage extends Actor {
  import Storage._
  implicit def system = context.system

  val db = DummyDB

  def receive = LoggingReceive(this) {
    case Store(Entry(key, count)) ⇒ db.save(key, count)
    case Get(key)                 ⇒ sender ! Entry(key, db.load(key).getOrElse(0L))
  }
}

object DummyDB {
  import Storage.StorageException
  var db = Map[String, Long]()

  @throws(classOf[StorageException])
  def save(key: String, value: Long): Unit = synchronized {
    if (11 <= value && value <= 14) throw new StorageException("Simulated store failure " + value)
    db += (key -> value)
  }

  @throws(classOf[StorageException])
  def load(key: String): Option[Long] = synchronized {
    db.get(key)
  }
}
//#all
