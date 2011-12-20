package akka.actor.mailbox

import akka.dispatch.CustomMailboxType

object BeanstalkBasedMailboxSpec {
  val config = """
    Beanstalkd-dispatcher {
      mailboxType = akka.actor.mailbox.BeanstalkBasedMailbox
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd", BeanstalkBasedMailboxSpec.config)
