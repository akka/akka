package akka.actor.mailbox

import akka.dispatch.CustomMailboxType

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd",
  new CustomMailboxType("akka.actor.mailbox.BeanstalkBasedMailbox"))
