package akka.actor.remote

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After}
import akka.config.RemoteAddress
import akka.actor.Agent
import akka.remote. {RemoteClient, RemoteServer}


class RemoteAgentSpec extends JUnitSuite {
  var server: RemoteServer = _

  val HOSTNAME = "localhost"
  val PORT = 9992

  @Before def startServer {
    val s = new RemoteServer()
    s.start(HOSTNAME, PORT)
    server = s
    Thread.sleep(1000)
  }

  @After def stopServer {
    val s = server
    server = null
    s.shutdown
    RemoteClient.shutdownAll
  }

  @Test def remoteAgentShouldInitializeProperly {
    val a = Agent(10,RemoteAddress(HOSTNAME,PORT))
    assert(a() == 10, "Remote agent should have the proper initial value")
    a(20)
    assert(a() == 20, "Remote agent should be updated properly")
    a.close
  }
}