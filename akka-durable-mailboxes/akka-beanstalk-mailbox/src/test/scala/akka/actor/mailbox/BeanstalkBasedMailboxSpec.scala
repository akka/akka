package akka.actor.mailbox

object BeanstalkBasedMailboxSpec {
  val config = """
    Beanstalkd-dispatcher {
      mailbox-type = akka.actor.mailbox.BeanstalkBasedMailboxType
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd", BeanstalkBasedMailboxSpec.config)
