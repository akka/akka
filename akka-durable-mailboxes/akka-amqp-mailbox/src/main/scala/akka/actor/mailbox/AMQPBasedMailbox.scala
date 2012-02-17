/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import java.util.concurrent.LinkedBlockingQueue

import com.rabbitmq.client.Channel
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.MessageProperties
import com.rabbitmq.client.QueueingConsumer

import akka.AkkaException
import akka.actor.ActorContext
import akka.dispatch.Envelope
import akka.event.Logging
import akka.actor.ActorRef
import akka.dispatch.MailboxType
import com.typesafe.config.Config

class AMQPBasedMailboxException(message: String) extends AkkaException(message)

class AMQPBasedMailboxType(config: Config) extends MailboxType {
  override def create(owner: ActorContext) = new AMQPBasedMailbox(owner)
}

class AMQPBasedMailbox(val owner: ActorContext) extends DurableMailbox(owner) with DurableMessageSerialization {

  private val settings = AMQPBasedMailboxExtension(owner.system)
  private val pool = settings.ChannelPool
  private val log = Logging(system, "AMQPBasedMailbox")
  private val consumerQueue = new LinkedBlockingQueue[QueueingConsumer.Delivery]
  private var consumer = withErrorHandling {
    createConsumer()
  }

  withErrorHandling {
    pool.withChannel { _.queueDeclare(name, true, false, false, null) }
  }

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    withErrorHandling {
      pool.withChannel { channel ⇒
        channel.basicPublish("", name, MessageProperties.PERSISTENT_BASIC, serialize(envelope))
      }
    }
  }

  def dequeue(): Envelope = withErrorHandling {
    val envelope = deserialize(consumer.nextDelivery.getBody)
    envelope
  }

  def numberOfMessages: Int = withErrorHandling {
    pool.withChannel { _.queueDeclare(name, true, false, false, null).getMessageCount }
  }

  def hasMessages: Boolean = !consumerQueue.isEmpty

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: java.io.IOException ⇒ {
        log.error("Communication with AMQP server failed, retrying operation.", e)
        try {
          pool.pool.returnObject(consumer.getChannel)
          consumer = createConsumer
          body
        } catch {
          case e: java.io.IOException ⇒
            throw new AMQPBasedMailboxException("AMQP server seems to be offline.")
        }
      }
    }
  }

  private def createConsumer() = {
    val channel = pool.pool.borrowObject.asInstanceOf[Channel]
    val consumer = new QueueingConsumer(channel, consumerQueue)
    channel.queueDeclare(name, true, false, false, null)
    channel.basicConsume(name, true, consumer)
    consumer
  }
}
