package akka.actor.mailbox

object BeanstalkBasedMailboxSpec {
  val config = """
    Beanstalkd-dispatcher {
      mailbox-type = akka.actor.mailbox.BeanstalkBasedMailboxType
      throughput = 1
      beanstalk {
        hostname = "127.0.0.1"
        port = 11400
      }
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class BeanstalkBasedMailboxSpec extends DurableMailboxSpec("Beanstalkd", BeanstalkBasedMailboxSpec.config) {

  lazy val beanstalkd = new ProcessBuilder("beanstalkd", "-b", "beanstalk", "-l", "127.0.0.1", "-p", "11400").start()

  override def atStartup(): Unit = {
    new java.io.File("beanstalk").mkdir()
    beanstalkd
    Thread.sleep(3000)
  }

  override def atTermination(): Unit = beanstalkd.destroy()

}

