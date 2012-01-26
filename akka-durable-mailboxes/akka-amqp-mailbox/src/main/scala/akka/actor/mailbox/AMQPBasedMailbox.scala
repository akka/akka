/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.GetResponse
import com.rabbitmq.client.MessageProperties

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

  private var pool = connect()

  val log = Logging(system, "AMQPBasedMailbox")

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in amqp-based mailbox [%s]".format(envelope))
    withErrorHandling {
      pool.withChannel { channel ⇒
        channel.basicPublish("", name, MessageProperties.PERSISTENT_BASIC, serialize(envelope))
      }
    }
  }

  def dequeue(): Envelope = withErrorHandling {
    try {
      pool.withChannel { channel ⇒
        channel.basicGet(name, true) match {
          case response: GetResponse ⇒ {
            val envelope = deserialize(response.getBody)
            log.debug("DEQUEUING message in amqp-based mailbox [%s]".format(envelope))
            envelope
          }
          case _ ⇒ null
        }
      }
    } catch {
      case e ⇒
        log.error(e, "Couldn't dequeue from amqp-based mailbox")
        throw e
    }
  }

  def numberOfMessages: Int = withErrorHandling {
    pool.withChannel { channel ⇒
      channel.queueDeclare(name, true, false, false, null).getMessageCount
    }
  }

  def hasMessages: Boolean = numberOfMessages > 0

  private[akka] def connect() = {
    import settings._
    val factory = new ConnectionFactory
    factory.setUsername(User)
    factory.setPassword(Password)
    factory.setVirtualHost(VirtualHost)
    factory.setHost(Hostname)
    factory.setPort(Port)
    new AMQPChannelPool(factory, name)
  }

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: java.io.IOException ⇒ {
        pool.close
        pool = connect()
        body
      }
      case e ⇒
        val error = new AMQPBasedMailboxException("Could not connect to AMQP server, due to: " + e.getMessage)
        log.error(error, error.getMessage)
        throw error
    }
  }
}
