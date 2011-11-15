package akka.actor.mailbox

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis", RedisDurableMailboxType)
