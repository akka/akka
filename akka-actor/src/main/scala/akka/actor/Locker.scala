/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.dispatch._
import akka.util.Duration
import java.util.concurrent.ConcurrentHashMap
import akka.event.DeathWatch

/**
 * Internal implementation detail for disposing of orphaned actors.
 */
private[akka] class Locker(
  scheduler: Scheduler,
  period: Duration,
  val provider: ActorRefProvider,
  val path: ActorPath,
  val deathWatch: DeathWatch) extends MinimalActorRef {

  class DavyJones extends Runnable {
    def run = {
      val iter = heap.entrySet.iterator
      while (iter.hasNext) {
        val soul = iter.next()
        deathWatch.subscribe(Locker.this, soul.getValue) // in case Terminated got lost somewhere
        soul.getValue match {
          case _: LocalRef ⇒ // nothing to do, they know what they signed up for
          case nonlocal    ⇒ nonlocal.stop() // try again in case it was due to a communications failure
        }
      }
    }
  }

  private val heap = new ConcurrentHashMap[ActorPath, InternalActorRef]

  scheduler.schedule(period, period, new DavyJones)

  override def sendSystemMessage(msg: SystemMessage): Unit = this.!(msg)

  override def !(msg: Any)(implicit sender: ActorRef = null): Unit = msg match {
    case Terminated(soul)      ⇒ heap.remove(soul.path)
    case ChildTerminated(soul) ⇒ heap.remove(soul.path)
    case soul: InternalActorRef ⇒
      heap.put(soul.path, soul)
      deathWatch.subscribe(this, soul)
      // now re-bind the soul so that it does not drown its parent
      soul match {
        case local: LocalActorRef ⇒
          val cell = local.underlying
          cell.parent = this
        case _ ⇒
      }
    case _ ⇒ // ignore
  }

  def childTerminated(parent: ActorRef, ct: ChildTerminated): Unit = {
    heap.get(parent.path) match {
      case null ⇒
      case ref  ⇒ ref.sendSystemMessage(ct)
    }
  }

  def shutdown(): Unit = {
    import scala.collection.JavaConverters._
    for (soul ← heap.values.asScala) {
      soul match {
        case l: LocalActorRef ⇒ l.underlying.dispatcher.detach(l.underlying)
        case _                ⇒
      }
    }
  }

}
