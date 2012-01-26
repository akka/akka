package akka.actor.mailbox

object AMQPBasedMailboxSpec {
  val config = """
    AMQP-dispatcher {
      mailboxType = akka.actor.mailbox.AMQPBasedMailboxType
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class AMQPBasedMailboxSpec extends DurableMailboxSpec("AMQP", AMQPBasedMailboxSpec.config)
