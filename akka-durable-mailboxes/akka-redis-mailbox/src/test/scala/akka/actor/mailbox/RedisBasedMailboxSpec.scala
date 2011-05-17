package akka.actor.mailbox

class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis", RedisDurableMailboxStorage)
