package akka.actor.mailbox

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd", BeanstalkDurableMailboxType)
