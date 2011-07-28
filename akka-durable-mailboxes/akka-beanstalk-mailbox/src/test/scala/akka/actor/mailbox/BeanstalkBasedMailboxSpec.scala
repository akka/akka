package akka.actor.mailbox

class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd", BeanstalkDurableMailboxStorage)
