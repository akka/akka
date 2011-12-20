package akka.actor.mailbox
import akka.dispatch.CustomMailboxType

object RedisBasedMailboxSpec {
  val config = """
    Redis-dispatcher {
      mailboxType = akka.actor.mailbox.RedisBasedMailbox
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis", RedisBasedMailboxSpec.config)
