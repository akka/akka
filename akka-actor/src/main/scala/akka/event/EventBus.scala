/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor.ActorRef
import akka.util.Index
import java.util.concurrent.ConcurrentSkipListSet
import java.util.Comparator

trait EventBus {
  type Event
  type Classifier
  type Subscriber

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean
  def unsubscribe(subscriber: Subscriber): Unit

  def publish(event: Event): Unit
}

trait ActorEventBus extends EventBus {
  type Subscriber = ActorRef
}

trait ActorClassifier { self: EventBus ⇒
  type Classifier = ActorRef
}

trait PredicateClassifier { self: EventBus ⇒
  type Classifier = Event ⇒ Boolean
}

trait EventType[T] { self: EventBus ⇒
  type Event = T
}

trait ClassifierType[T] { self: EventBus ⇒
  type Classifier = T
}

trait LookupClassification { self: EventBus ⇒
  protected final val subscribers = new Index[Classifier, Subscriber]

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = subscribers.put(to, subscriber)
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = subscribers.remove(from, subscriber)
  def unsubscribe(subscriber: Subscriber): Unit = subscribers.removeValue(subscriber)

  protected def classify(event: Event): Classifier

  protected def publish(event: Event, subscriber: Subscriber): Unit

  def publish(event: Event): Unit =
    subscribers.valueIterator(classify(event)).foreach(publish(event, _))
}

trait ScanningClassification { self: EventBus ⇒
  protected final val subscribers = new ConcurrentSkipListSet[(Classifier, Subscriber)](ordering)

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = subscribers.add((to, subscriber))
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = subscribers.remove((from, subscriber))
  def unsubscribe(subscriber: Subscriber): Unit = {
    val i = subscribers.iterator()
    while (i.hasNext) {
      val e = i.next()
      if (subscriber == e._2) i.remove()
    }
  }

  protected def ordering: Comparator[(Classifier, Subscriber)]

  protected def matches(classifier: Classifier, event: Event): Boolean

  protected def publish(event: Event, subscriber: Subscriber): Unit

  def publish(event: Event): Unit = {
    val currentSubscribers = subscribers.iterator()
    while (currentSubscribers.hasNext) {
      val (classifier, subscriber) = currentSubscribers.next()
      if (matches(classifier, event)) publish(event, subscriber)
    }
  }
}

trait ActorClassification { self: ActorEventBus with ActorClassifier ⇒
  import java.util.concurrent.ConcurrentHashMap
  import scala.annotation.tailrec

  def mapSize: Int

  protected val mappings = new ConcurrentHashMap[ActorRef, Vector[ActorRef]](mapSize)

  @tailrec
  protected final def associate(monitored: ActorRef, monitor: ActorRef): Boolean = {
    val current = mappings get monitored
    current match {
      case null ⇒
        if (monitored.isShutdown) false
        else {
          if (mappings.putIfAbsent(monitored, Vector(monitor)) ne null) associate(monitored, monitor)
          else {
            if (monitored.isShutdown) !dissociate(monitored, monitor)
            else true
          }
        }
      case raw: Vector[_] ⇒
        val v = raw.asInstanceOf[Vector[ActorRef]]
        if (monitored.isShutdown) false
        if (v.contains(monitor)) true
        else {
          val added = v :+ monitor
          if (!mappings.replace(monitored, v, added)) associate(monitored, monitor)
          else {
            if (monitored.isShutdown) !dissociate(monitored, monitor)
            else true
          }
        }
    }
  }

  protected final def dissociate(monitored: ActorRef): Iterable[ActorRef] = {
    @tailrec
    def dissociateAsMonitored(monitored: ActorRef): Iterable[ActorRef] = {
      val current = mappings get monitored
      current match {
        case null ⇒ Vector.empty[ActorRef]
        case raw: Vector[_] ⇒
          val v = raw.asInstanceOf[Vector[ActorRef]]
          if (!mappings.remove(monitored, v)) dissociateAsMonitored(monitored)
          else v
      }
    }

    def dissociateAsMonitor(monitor: ActorRef): Unit = {
      val i = mappings.entrySet.iterator
      while (i.hasNext()) {
        val entry = i.next()
        val v = entry.getValue
        v match {
          case raw: Vector[_] ⇒
            val monitors = raw.asInstanceOf[Vector[ActorRef]]
            if (monitors.contains(monitor))
              dissociate(entry.getKey, monitor)
          case _ ⇒ //Dun care
        }
      }
    }

    try { dissociateAsMonitored(monitored) } finally { dissociateAsMonitor(monitored) }
  }

  @tailrec
  protected final def dissociate(monitored: ActorRef, monitor: ActorRef): Boolean = {
    val current = mappings get monitored
    current match {
      case null ⇒ false
      case raw: Vector[_] ⇒
        val v = raw.asInstanceOf[Vector[ActorRef]]
        val removed = v.filterNot(monitor ==)
        if (removed eq v) false
        else if (removed.isEmpty) {
          if (!mappings.remove(monitored, v)) dissociate(monitored, monitor)
          else true
        } else {
          if (!mappings.replace(monitored, v, removed)) dissociate(monitored, monitor)
          else true
        }
    }
  }

  protected def classify(event: Event): Classifier

  def publish(event: Event): Unit = mappings.get(classify(event)) match {
    case null ⇒
    case raw: Vector[_] ⇒
      val v = raw.asInstanceOf[Vector[ActorRef]]
      v foreach { _ ! event }
  }

  def subscribe(subscriber: Subscriber, to: Classifier): Boolean = associate(to, subscriber)
  def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = dissociate(from, subscriber)
  def unsubscribe(subscriber: Subscriber): Unit = dissociate(subscriber)
}