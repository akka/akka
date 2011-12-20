package akka.actor.mailbox
import akka.dispatch.CustomMailboxType

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis",
  new CustomMailboxType("akka.actor.mailbox.RedisBasedMailbox"))
