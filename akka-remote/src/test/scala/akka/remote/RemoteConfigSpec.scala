package akka.remote

import akka.testkit.AkkaSpec
import akka.actor.ExtendedActorSystem
import akka.util.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteConfigSpec extends AkkaSpec(
  """
  akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
    }
  }
  """) {

  "RemoteExtension" must {
    "be able to parse remote and cluster config elements" in {
      val settings = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].remoteSettings

      //SharedSettings

      {
        import settings._

        RemoteTransport must equal("akka.remote.netty.NettyRemoteSupport")
        BackoffTimeout must equal(0 seconds)
        LogReceivedMessages must equal(false)
        LogSentMessages must equal(false)
      }

      //ServerSettings

      {
        import settings.serverSettings._
        SecureCookie must be(None)
        UsePassiveConnections must equal(true)
        Port must equal(2552)
        MessageFrameSize must equal(1048576L)
        RequireCookie must equal(false)
        UntrustedMode must equal(false)
        Backlog must equal(4096)
        ExecutionPoolKeepAlive must equal(1 minute)
        ExecutionPoolSize must equal(4)
        MaxChannelMemorySize must equal(0)
        MaxTotalMemorySize must equal(0)
      }

      //ClientSettings

      {
        import settings.clientSettings._
        SecureCookie must be(None)
        ReconnectDelay must equal(5 seconds)
        ReadTimeout must equal(1 hour)
        ReconnectionTimeWindow must equal(10 minutes)
        ConnectionTimeout must equal(10 seconds)
      }

      // TODO cluster config will go into akka-cluster/reference.conf when we enable that module
      settings.SeedNodes must be('empty)
    }
  }
}
