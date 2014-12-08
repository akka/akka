/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import akka.persistence.JournalProtocol.ReplayMessagesSuccess

import scala.annotation.tailrec
import scala.collection.breakOut
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.ActorPath
import akka.persistence.serialization.Message

object AtLeastOnceDelivery {

  /**
   * Snapshot of current `AtLeastOnceDelivery` state. Can be retrieved with
   * [[AtLeastOnceDelivery#getDeliverySnapshot]] and saved with [[PersistentActor#saveSnapshot]].
   * During recovery the snapshot received in [[SnapshotOffer]] should be set
   * with [[AtLeastOnceDelivery.setDeliverySnapshot]].
   */
  @SerialVersionUID(1L)
  case class AtLeastOnceDeliverySnapshot(currentDeliveryId: Long, unconfirmedDeliveries: immutable.Seq[UnconfirmedDelivery])
    extends Message {

    /**
     * Java API
     */
    def getUnconfirmedDeliveries: java.util.List[UnconfirmedDelivery] = {
      import scala.collection.JavaConverters._
      unconfirmedDeliveries.asJava
    }

  }

  /**
   * @see [[AtLeastOnceDelivery#warnAfterNumberOfUnconfirmedAttempts]]
   */
  @SerialVersionUID(1L)
  case class UnconfirmedWarning(unconfirmedDeliveries: immutable.Seq[UnconfirmedDelivery]) {
    /**
     * Java API
     */
    def getUnconfirmedDeliveries: java.util.List[UnconfirmedDelivery] = {
      import scala.collection.JavaConverters._
      unconfirmedDeliveries.asJava
    }
  }

  /**
   * Information about a message that has not been confirmed. Included in [[UnconfirmedWarning]]
   * and [[AtLeastOnceDeliverySnapshot]].
   */
  case class UnconfirmedDelivery(deliveryId: Long, destination: ActorPath, message: Any) {
    /**
     * Java API
     */
    def getMessage(): AnyRef = message.asInstanceOf[AnyRef]
  }

  /**
   * @see [[AtLeastOnceDelivery#maxUnconfirmedMessages]]
   */
  class MaxUnconfirmedMessagesExceededException(message: String) extends RuntimeException(message)

  private object Internal {
    case class Delivery(destination: ActorPath, message: Any, timestamp: Long, attempt: Int)
    case object RedeliveryTick
  }

}

/**
 * Mix-in this trait with your `PersistentActor` to send messages with at-least-once
 * delivery semantics to destinations. It takes care of re-sending messages when they
 * have not been confirmed within a configurable timeout. Use the [[#deliver]] method to
 * send a message to a destination. Call the [[#confirmDelivery]] method when the destination
 * has replied with a confirmation message.
 *
 * At-least-once delivery implies that original message send order is not always retained
 * and the destination may receive duplicate messages due to possible resends.
 *
 * The interval between redelivery attempts can be defined by [[#redeliverInterval]].
 * After a number of delivery attempts a [[AtLeastOnceDelivery.UnconfirmedWarning]] message
 * will be sent to `self`. The re-sending will still continue, but you can choose to call
 * [[#confirmDelivery]] to cancel the re-sending.
 *
 * The `AtLeastOnceDelivery` trait has a state consisting of unconfirmed messages and a
 * sequence number. It does not store this state itself. You must persist events corresponding
 * to the `deliver` and `confirmDelivery` invocations from your `PersistentActor` so that the
 * state can be restored by calling the same methods during the recovery phase of the
 * `PersistentActor`. Sometimes these events can be derived from other business level events,
 * and sometimes you must create separate events. During recovery calls to `deliver`
 * will not send out the message, but it will be sent later if no matching `confirmDelivery`
 * was performed.
 *
 * Support for snapshots is provided by [[#getDeliverySnapshot]] and [[#setDeliverySnapshot]].
 * The `AtLeastOnceDeliverySnapshot` contains the full delivery state, including unconfirmed messages.
 * If you need a custom snapshot for other parts of the actor state you must also include the
 * `AtLeastOnceDeliverySnapshot`. It is serialized using protobuf with the ordinary Akka
 * serialization mechanism. It is easiest to include the bytes of the `AtLeastOnceDeliverySnapshot`
 * as a blob in your custom snapshot.
 */
trait AtLeastOnceDelivery extends Eventsourced {
  import AtLeastOnceDelivery._
  import AtLeastOnceDelivery.Internal._

  /**
   * Interval between redelivery attempts.
   *
   * The default value can be configured with the
   * `akka.persistence.at-least-once-delivery.redeliver-interval`
   * configuration key. This method can be overridden by implementation classes to return
   * non-default values.
   */
  def redeliverInterval: FiniteDuration = defaultRedeliverInterval

  private val defaultRedeliverInterval: FiniteDuration =
    Persistence(context.system).settings.atLeastOnceDelivery.redeliverInterval

  /**
   * Maximum number of unconfirmed messages that will be sent at each redelivery burst
   * (burst frequency is half of the redelivery interval).
   * If there's a lot of unconfirmed messages (e.g. if the destination is not available for a long time),
   * this helps to prevent an overwhelming amount of messages to be sent at once.
   *
   * The default value can be configured with the
   * `akka.persistence.at-least-once-delivery.redelivery-burst-limit`
   * configuration key. This method can be overridden by implementation classes to return
   * non-default values.
   */
  def redeliveryBurstLimit: Int = defaultRedeliveryBurstLimit

  private val defaultRedeliveryBurstLimit: Int =
    Persistence(context.system).settings.atLeastOnceDelivery.redeliveryBurstLimit

  /**
   * After this number of delivery attempts a [[AtLeastOnceDelivery.UnconfirmedWarning]] message
   * will be sent to `self`. The count is reset after a restart.
   *
   * The default value can be configured with the
   * `akka.persistence.at-least-once-delivery.warn-after-number-of-unconfirmed-attempts`
   * configuration key. This method can be overridden by implementation classes to return
   * non-default values.
   */
  def warnAfterNumberOfUnconfirmedAttempts: Int = defaultWarnAfterNumberOfUnconfirmedAttempts

  private val defaultWarnAfterNumberOfUnconfirmedAttempts: Int =
    Persistence(context.system).settings.atLeastOnceDelivery.warnAfterNumberOfUnconfirmedAttempts

  /**
   * Maximum number of unconfirmed messages that this actor is allowed to hold in memory.
   * If this number is exceed [[#deliver]] will not accept more messages and it will throw
   * [[AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException]].
   *
   * The default value can be configured with the
   * `akka.persistence.at-least-once-delivery.max-unconfirmed-messages`
   * configuration key. This method can be overridden by implementation classes to return
   * non-default values.
   */
  def maxUnconfirmedMessages: Int = defaultMaxUnconfirmedMessages

  private val defaultMaxUnconfirmedMessages: Int =
    Persistence(context.system).settings.atLeastOnceDelivery.maxUnconfirmedMessages

  private val redeliverTask = {
    import context.dispatcher
    val interval = redeliverInterval / 2
    context.system.scheduler.schedule(interval, interval, self, RedeliveryTick)
  }

  private var deliverySequenceNr = 0L
  private var unconfirmed = immutable.SortedMap.empty[Long, Delivery]

  private def nextDeliverySequenceNr(): Long = {
    deliverySequenceNr += 1
    deliverySequenceNr
  }

  /**
   * Scala API: Send the message created by the `deliveryIdToMessage` function to
   * the `destination` actor. It will retry sending the message until
   * the delivery is confirmed with [[#confirmDelivery]]. Correlation
   * between `deliver` and `confirmDelivery` is performed with the
   * `deliveryId` that is provided as parameter to the `deliveryIdToMessage`
   * function. The `deliveryId` is typically passed in the message to the
   * destination, which replies with a message containing the same `deliveryId`.
   *
   * The `deliveryId` is a strictly monotonically increasing sequence number without
   * gaps. The same sequence is used for all destinations of the actor, i.e. when sending
   * to multiple destinations the destinations will see gaps in the sequence if no
   * translation is performed.
   *
   * During recovery this method will not send out the message, but it will be sent
   * later if no matching `confirmDelivery` was performed.
   *
   * This method will throw [[AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException]]
   * if [[numberOfUnconfirmed]] is greater than or equal to [[#maxUnconfirmedMessages]].
   */
  def deliver(destination: ActorPath, deliveryIdToMessage: Long ⇒ Any): Unit = {
    if (unconfirmed.size >= maxUnconfirmedMessages)
      throw new MaxUnconfirmedMessagesExceededException(
        s"Too many unconfirmed messages, maximum allowed is [$maxUnconfirmedMessages]")

    val deliveryId = nextDeliverySequenceNr()
    val now = if (recoveryRunning) { System.nanoTime() - redeliverInterval.toNanos } else System.nanoTime()
    val d = Delivery(destination, deliveryIdToMessage(deliveryId), now, attempt = 0)

    if (recoveryRunning)
      unconfirmed = unconfirmed.updated(deliveryId, d)
    else
      send(deliveryId, d, now)
  }

  /**
   * Call this method when a message has been confirmed by the destination,
   * or to abort re-sending.
   * @see [[#deliver]]
   * @return `true` the first time the `deliveryId` is confirmed, i.e. `false` for duplicate confirm
   */
  def confirmDelivery(deliveryId: Long): Boolean = {
    if (unconfirmed.contains(deliveryId)) {
      unconfirmed -= deliveryId
      true
    } else false
  }

  /**
   * Number of messages that have not been confirmed yet.
   */
  def numberOfUnconfirmed: Int = unconfirmed.size

  private def redeliverOverdue(): Unit = {
    val now = System.nanoTime()
    val deadline = now - redeliverInterval.toNanos
    var warnings = Vector.empty[UnconfirmedDelivery]

    unconfirmed
      .iterator
      .filter { case (_, delivery) ⇒ delivery.timestamp <= deadline }
      .take(redeliveryBurstLimit)
      .foreach {
        case (deliveryId, delivery) ⇒
          send(deliveryId, delivery, now)

          if (delivery.attempt == warnAfterNumberOfUnconfirmedAttempts)
            warnings :+= UnconfirmedDelivery(deliveryId, delivery.destination, delivery.message)
      }

    if (warnings.nonEmpty)
      self ! UnconfirmedWarning(warnings)
  }

  private def send(deliveryId: Long, d: Delivery, timestamp: Long): Unit = {
    context.actorSelection(d.destination) ! d.message
    unconfirmed = unconfirmed.updated(deliveryId, d.copy(timestamp = timestamp, attempt = d.attempt + 1))
  }

  /**
   * Full state of the `AtLeastOnceDelivery`. It can be saved with [[PersistentActor#saveSnapshot]].
   * During recovery the snapshot received in [[SnapshotOffer]] should be set
   * with [[#setDeliverySnapshot]].
   *
   * The `AtLeastOnceDeliverySnapshot` contains the full delivery state, including unconfirmed messages.
   * If you need a custom snapshot for other parts of the actor state you must also include the
   * `AtLeastOnceDeliverySnapshot`. It is serialized using protobuf with the ordinary Akka
   * serialization mechanism. It is easiest to include the bytes of the `AtLeastOnceDeliverySnapshot`
   * as a blob in your custom snapshot.
   */
  def getDeliverySnapshot: AtLeastOnceDeliverySnapshot =
    AtLeastOnceDeliverySnapshot(deliverySequenceNr,
      unconfirmed.map { case (deliveryId, d) ⇒ UnconfirmedDelivery(deliveryId, d.destination, d.message) }(breakOut))

  /**
   * If snapshot from [[#getDeliverySnapshot]] was saved it will be received during recovery
   * in a [[SnapshotOffer]] message and should be set with this method.
   */
  def setDeliverySnapshot(snapshot: AtLeastOnceDeliverySnapshot): Unit = {
    deliverySequenceNr = snapshot.currentDeliveryId
    val now = System.nanoTime()
    unconfirmed = snapshot.unconfirmedDeliveries.map(d ⇒
      d.deliveryId -> Delivery(d.destination, d.message, now, 0))(breakOut)
  }

  /**
   * INTERNAL API
   */
  override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]): Unit = {
    redeliverTask.cancel()
    super.aroundPreRestart(reason, message)
  }

  /**
   * INTERNAL API
   */
  override protected[akka] def aroundPostStop(): Unit = {
    redeliverTask.cancel()
    super.aroundPostStop()
  }

  override private[persistence] def onReplaySuccess(): Unit = {
    redeliverOverdue()
    super.onReplaySuccess()
  }

  /**
   * INTERNAL API
   */
  override protected[akka] def aroundReceive(receive: Receive, message: Any): Unit =
    message match {
      case RedeliveryTick ⇒
        redeliverOverdue()

      case x ⇒
        super.aroundReceive(receive, message)
    }
}

/**
 * Java API: Use this class instead of `UntypedPersistentActor` to send messages
 * with at-least-once delivery semantics to destinations.
 * Full documentation in [[AtLeastOnceDelivery]].
 *
 * @see [[AtLeastOnceDelivery]]
 */
abstract class UntypedPersistentActorWithAtLeastOnceDelivery extends UntypedPersistentActor with AtLeastOnceDelivery {
  /**
   * Java API: Send the message created by the `deliveryIdToMessage` function to
   * the `destination` actor. It will retry sending the message until
   * the delivery is confirmed with [[#confirmDelivery]]. Correlation
   * between `deliver` and `confirmDelivery` is performed with the
   * `deliveryId` that is provided as parameter to the `deliveryIdToMessage`
   * function. The `deliveryId` is typically passed in the message to the
   * destination, which replies with a message containing the same `deliveryId`.
   *
   * The `deliveryId` is a strictly monotonically increasing sequence number without
   * gaps. The same sequence is used for all destinations, i.e. when sending to
   * multiple destinations the destinations will see gaps in the sequence if no
   * translation is performed.
   *
   * During recovery this method will not send out the message, but it will be sent
   * later if no matching `confirmDelivery` was performed.
   *
   * This method will throw [[AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException]]
   * if [[numberOfUnconfirmed]] is greater than or equal to [[#maxUnconfirmedMessages]].
   */
  def deliver(destination: ActorPath, deliveryIdToMessage: akka.japi.Function[java.lang.Long, Object]): Unit =
    super.deliver(destination, id ⇒ deliveryIdToMessage.apply(id))
}

/**
 * Java API compatible with lambda expressions
 *
 * Use this class instead of `UntypedPersistentActor` to send messages
 * with at-least-once delivery semantics to destinations.
 * Full documentation in [[AtLeastOnceDelivery]].
 *
 * @see [[AtLeastOnceDelivery]]
 */
abstract class AbstractPersistentActorWithAtLeastOnceDelivery extends AbstractPersistentActor with AtLeastOnceDelivery {
  /**
   * Java API: Send the message created by the `deliveryIdToMessage` function to
   * the `destination` actor. It will retry sending the message until
   * the delivery is confirmed with [[#confirmDelivery]]. Correlation
   * between `deliver` and `confirmDelivery` is performed with the
   * `deliveryId` that is provided as parameter to the `deliveryIdToMessage`
   * function. The `deliveryId` is typically passed in the message to the
   * destination, which replies with a message containing the same `deliveryId`.
   *
   * The `deliveryId` is a strictly monotonically increasing sequence number without
   * gaps. The same sequence is used for all destinations, i.e. when sending to
   * multiple destinations the destinations will see gaps in the sequence if no
   * translation is performed.
   *
   * During recovery this method will not send out the message, but it will be sent
   * later if no matching `confirmDelivery` was performed.
   *
   * This method will throw [[AtLeastOnceDelivery.MaxUnconfirmedMessagesExceededException]]
   * if [[numberOfUnconfirmed]] is greater than or equal to [[#maxUnconfirmedMessages]].
   */
  def deliver(destination: ActorPath, deliveryIdToMessage: akka.japi.Function[java.lang.Long, Object]): Unit =
    super.deliver(destination, id ⇒ deliveryIdToMessage.apply(id))
}
