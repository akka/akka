package akka.actor.mailbox

object RedisBasedMailboxSpec {
  val config = """
    Redis-dispatcher {
      mailbox-type = akka.actor.mailbox.RedisBasedMailboxType
      throughput = 1
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis", RedisBasedMailboxSpec.config)
