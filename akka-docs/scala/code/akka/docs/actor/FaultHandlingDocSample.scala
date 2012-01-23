/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor

//#all
import akka.actor._
import akka.actor.FaultHandlingStrategy._
import akka.dispatch.Await
import akka.util.duration._
import akka.util.Timeout
import akka.event.LoggingReceive
import com.typesafe.config.ConfigFactory

object FaultHandlingDocSample extends App {
  import Worker._

  val config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    akka.actor.debug {
      receive = on
      lifecycle = on
    }
    """)

  val system = ActorSystem("FaultToleranceSample", config)

  val worker = system.actorOf(Props[Worker], name = "worker")
  worker ! Start
  worker ! Report

  20.seconds.sleep()
  system.shutdown()
}

object Worker {
  case object Start
  case object Report
}

class Worker extends Actor with ActorLogging {
  import Worker._
  import Counter._
  implicit def system = context.system

  val a = context.actorOf(Counter.props, name = "counter")

  def receive = LoggingReceive(this) {
    case Start ⇒
      a ! Increment(10)
      a ! Increment(1)
      a ! Increment(1)
      a ! Increment(1)
      a ! Increment(1)
      a ! Increment(1)
      a ! Increment(1)
      a ! Increment(1)

    case Report ⇒
      a ! GetCurrentCount

    case CurrentCount(key, count) ⇒
      log.info("Current count for [{}] is [{}]", key, count)
  }
}

object Counter {
  // Messages
  case class Increment(n: Int)
  case object GetCurrentCount
  case class CurrentCount(key: String, count: Long)
  case object Reconnect

  // Props of the Counter with fault handler strategy.
  // Note that the Counter is the Supervisor of the child Storage
  // and it uses the fault handling strategy defined in the Props
  // of the Counter to handle failures from the child Storage.
  val props = {
    // FIXME withinTimeRange should be duration
    val strategy = OneForOneStrategy({
      case _: Storage.StorageException ⇒ Restart
    }: Decider, maxNrOfRetries = Some(3), withinTimeRange = Some(5000))
    Props[Counter].withFaultHandler(strategy)
  }
}

class Counter extends Actor {
  import Counter._
  import Storage._
  implicit def system = context.system

  var count = 0L
  var storage: Option[ActorRef] = None
  val key = context.self.path.name

  override def preStart() {
    initStorage()
    storage foreach { s ⇒
      // We need the initial value to be able to operate
      implicit val timeout = Timeout(5 seconds)
      Await.result(s ? Get(key), timeout.duration) match {
        case Entry(k, v) if k == key ⇒ count = v
      }
    }
  }

  def initStorage() {
    // The child storage is restarted in case of failure, but after 3 restarts,
    // and still failing it will be stopped. Better to back-off than continuously
    // failing. When it has been stopped we will schedule a Reconnect after a delay.
    // Watch the child so we receive Terminated message when it has been terminated.
    storage = Some(context.watch(context.actorOf(Props[Storage], name = "storage")))
  }

  def storeCount() {
    // Delegate dangerous work to child, to protect our valuable state.
    // We can continue without storage.
    storage foreach { _ ! Store(Entry(key, count)) }
  }

  def receive = LoggingReceive(this) {
    case Increment(n) ⇒
      count += n
      storeCount()

    case GetCurrentCount ⇒
      sender ! CurrentCount(key, count)

    case Terminated(actorRef) if Some(actorRef) == storage ⇒
      // After 3 restarts the storage child is stopped.
      // We receive Terminated because we watch the child, see initStorage.
      storage = None
      // Try to re-establish storage after while
      context.system.scheduler.scheduleOnce(10 seconds, self, Reconnect)

    case Reconnect ⇒
      // Re-establish storage after the scheduled delay
      initStorage()
      storeCount()
  }

}

object Storage {
  case class Store(entry: Entry)
  case class Get(key: String)
  case class Entry(key: String, value: Long)
  class StorageException(msg: String) extends RuntimeException(msg)
}

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
