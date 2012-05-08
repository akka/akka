package akka.amqp

import com.rabbitmq.client.AMQP.BasicProperties
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import util.control.Exception
import com.rabbitmq.client.{ ShutdownSignalException, Envelope, DefaultConsumer }
import java.io.IOException
import akka.actor.ActorRef
import akka.event.Logging

case class ReturnedMessage(replyCode: Int,
                           replyText: String,
                           exchange: String,
                           routingKey: String,
                           properties: BasicProperties,
                           body: Array[Byte])

case class Delivery(payload: Array[Byte],
                    routingKey: String,
                    deliveryTag: Long,
                    isRedeliver: Boolean,
                    properties: BasicProperties,
                    sender: DurableConsumer) {

  def acknowledge() {
    sender.acknowledge(deliveryTag)
  }

  def reject() {
    sender.reject(deliveryTag)
  }
}

class DurableConsumer(durableConnection: DurableConnection,
                      queue: Queue,
                      deliveryHandler: ActorRef,
                      autoAcknowledge: Boolean,
                      queueBindings: QueueBinding*) extends DurableChannel(durableConnection) {
  outer ⇒

  private val log = Logging(durableConnection.connectionProperties.system, this.getClass)

  val consumerTag = new AtomicReference[Option[String]](None)
  val latch = new CountDownLatch(1)
  onAvailable {
    channel ⇒
      val queueName = queue match {
        case managed: ManagedQueue ⇒
          log.debug("Declaring queue {}", managed)
          managed.declare(channel).name
        case unmanaged: UnmanagedQueue ⇒ unmanaged.name
      }
      queueBindings.foreach { binding ⇒
        binding.exchange match {
          case managed: ManagedExchange ⇒
            log.debug("Declaring exchange {}", managed)
            managed.declare(channel)
          case _ ⇒ ()
        }
        binding.bind(channel, queueName)
      }
      val tag = channel.basicConsume(queueName, autoAcknowledge, new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String, envelope: Envelope, properties: BasicProperties, body: Array[Byte]) {
          import envelope._
          deliveryHandler ! Delivery(body, getRoutingKey, getDeliveryTag, isRedeliver, properties, outer)
        }
      })
      consumerTag.set(Some(tag))
      latch.countDown()
  }

  def awaitStart(timeout: Long = 5, unit: TimeUnit = TimeUnit.SECONDS) = {
    latch.await(timeout, unit)
  }

  def acknowledge(deliveryTag: Long, multiple: Boolean = false) {
    if (!channelActor.isTerminated) channelActor ! ExecuteCallback(_.basicAck(deliveryTag, multiple))
  }

  def reject(deliveryTag: Long, reQueue: Boolean = false) {
    if (!channelActor.isTerminated) channelActor ! ExecuteCallback(_.basicReject(deliveryTag, reQueue))
  }

  override def stop() {
    if (!channelActor.isTerminated) {
      for (tag ← consumerTag.get()) {
        channelActor ! ExecuteCallback { channel ⇒
          Exception.ignoring(classOf[ShutdownSignalException], classOf[IOException]) {
            channel.basicCancel(tag)
          }
        }
      }
    }
    super.stop()
  }
}