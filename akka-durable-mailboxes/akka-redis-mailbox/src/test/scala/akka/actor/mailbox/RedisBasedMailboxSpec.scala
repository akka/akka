package akka.actor.mailbox

object RedisBasedMailboxSpec {
  val config = """
    Redis-dispatcher {
      mailbox-type = akka.actor.mailbox.RedisBasedMailboxType
      throughput = 1
      redis {
        hostname = "127.0.0.1"
        port = 6479
      }
    }
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RedisBasedMailboxSpec extends DurableMailboxSpec("Redis", RedisBasedMailboxSpec.config) {

  lazy val redisServer = new ProcessBuilder("redis-server", "-").start()

  override def atStartup(): Unit = {
    new java.io.File("redis").mkdir()

    val out = redisServer.getOutputStream

    val config = """
      port 6479
      bind 127.0.0.1
      dir redis
      """.getBytes("UTF-8")

    try {
      out.write(config)
      out.close()

      streamMustContain(redisServer.getInputStream, "ready to accept connections on port")
    } catch {
      case e ⇒ redisServer.destroy(); throw e
    }
  }

  override def atTermination(): Unit = redisServer.destroy()

}
