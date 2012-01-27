/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

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

  private val pool = settings.ChannelPool

  private val log = Logging(system, "AMQPBasedMailbox")

  withErrorHandling {
    pool.withChannel { _.queueDeclare(name, true, false, false, null) }
  }

  def enqueue(receiver: ActorRef, envelope: Envelope) {
    log.debug("ENQUEUING message in amqp-based mailbox [{}]", envelope)
    withErrorHandling {
      pool.withChannel { channel ⇒
        channel.basicPublish("", name, MessageProperties.PERSISTENT_BASIC, serialize(envelope))
      }
    }
  }

  def dequeue(): Envelope = withErrorHandling {
    pool.withChannel { channel ⇒
      channel.basicGet(name, true) match {
        case response: GetResponse ⇒ {
          val envelope = deserialize(response.getBody)
          log.debug("DEQUEUING message in amqp-based mailbox [{}]", envelope)
          envelope
        }
        case _ ⇒ null
      }
    }
  }

  def numberOfMessages: Int = withErrorHandling {
    pool.withChannel { _.queueDeclare(name, true, false, false, null).getMessageCount }
  }

  // check by message count because rabbit library does not
  // provide a call to check if messages are in the queue
  def hasMessages: Boolean = numberOfMessages > 0

  private def withErrorHandling[T](body: ⇒ T): T = {
    try {
      body
    } catch {
      case e: java.io.IOException ⇒ {
        log.error("Communication with AMQP server failed, retrying operation.", e)
        try {
          body
        } catch {
          case e: java.io.IOException ⇒
            throw new AMQPBasedMailboxException("AMQP server seems to be offline.")
        }
      }
    }
  }
}
