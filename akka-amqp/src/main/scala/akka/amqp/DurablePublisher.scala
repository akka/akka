package akka.amqp

import com.rabbitmq.client.AMQP.BasicProperties
import java.util.{ Collections, TreeSet }
import java.util.concurrent.{ TimeoutException, TimeUnit, CountDownLatch, ConcurrentHashMap }
import akka.util.Duration
import akka.util.duration._
import akka.event.Logging
import akka.dispatch.{ Await, Promise, Future }
import com.rabbitmq.client.{ ConfirmListener, ReturnListener }
import akka.actor.ActorSystem

case class Message(payload: Array[Byte],
                   routingKey: String,
                   mandatory: Boolean = false,
                   immediate: Boolean = false,
                   properties: Option[BasicProperties] = None)

class DurablePublisher(durableConnection: DurableConnection,
                       exchange: Exchange,
                       persistent: Boolean = false) extends DurableChannel(durableConnection, persistent) {

  implicit val system = durableConnection.connectionProperties.system
  protected val log = Logging(system, this.getClass)

  val latch = new CountDownLatch(1)
  onAvailable { channel ⇒
    exchange match {
      case managed: ManagedExchange ⇒
        if (log.isDebugEnabled) log.debug("Declaring exchange %s".format(managed))
        managed.declare(channel)
      case _ ⇒
    }
    latch.countDown()
  }

  val exchangeName = exchange match {
    case named: NamedExchange ⇒ named.name
    case _                    ⇒ ""
  }

  def awaitStart(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS) = {
    latch.await(timeout, unit)
  }

  def onReturn(callback: ReturnedMessage ⇒ Unit) {
    onAvailable { channel ⇒
      channel.addReturnListener(new ReturnListener {
        def handleReturn(replyCode: Int, replyText: String, exchange: String, routingKey: String, properties: BasicProperties, body: Array[Byte]) {
          callback.apply(ReturnedMessage(replyCode, replyText, exchange, routingKey, properties, body))
        }
      })
    }
  }

  def publish(message: Message, timeout: Long = 5000): Future[Unit] = {
    val future = Promise[Unit]
    try {
      channelActor ! ExecuteCallback { channel ⇒
        import message._
        if (log.isDebugEnabled) log.debug("Publishing on '%s': %s".format(exchangeName, message))
        channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), payload)
        future.success(())
      }
    } catch {
      case e: Exception ⇒ future.failure(e)
    }

    future
  }
}

sealed trait Confirm
case object Ack extends Confirm
case object Nack extends Confirm

trait ConfirmingPublisher extends ConfirmListener {
  this: DurablePublisher ⇒

  import scala.collection.JavaConverters._

  private val confirmHandles = new ConcurrentHashMap[Long, Promise[Confirm]]().asScala
  private val unconfirmedSet = Collections.synchronizedSortedSet(new TreeSet[Long]());

  onAvailable { channel ⇒
    channel.confirmSelect()
    channel.addConfirmListener(this)
  }

  def publishConfirmed(message: Message, timeout: Duration = (5 seconds)): Future[Confirm] = {
    val future = Promise[Confirm]
    channelActor ! ExecuteCallback { channel ⇒
      import message._
      if (log.isDebugEnabled) log.debug("Publishing on '%s': %s".format(exchangeName, message))
      val seqNo = channel.getNextPublishSeqNo
      unconfirmedSet.add(seqNo)
      confirmHandles.put(seqNo, future)
      Future {
        Await.ready(future, timeout)
      }.onFailure { case te: TimeoutException ⇒ confirmHandles.remove(seqNo) }
      channel.basicPublish(exchangeName, routingKey, mandatory, immediate, properties.getOrElse(null), payload)
    }
    future
  }

  private[amqp] def handleAck(seqNo: Long, multiple: Boolean) {
    handleConfirm(seqNo, multiple, true)
  }

  private[amqp] def handleNack(seqNo: Long, multiple: Boolean) {
    handleConfirm(seqNo, multiple, false)
  }

  private def handleConfirm(seqNo: Long, multiple: Boolean, ack: Boolean) {
    if (multiple) {
      val headSet = unconfirmedSet.headSet(seqNo + 1)
      headSet.asScala.foreach(complete)
      headSet.clear();
    } else {
      unconfirmedSet.remove(seqNo);
      complete(seqNo)
    }

    def complete(seqNo: Long) {
      confirmHandles.remove(seqNo).foreach(_.success(if (ack) Ack else Nack))
    }
  }
}
