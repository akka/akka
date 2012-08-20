package akka.remote.actmote

class ActorManagedRemotingInteropSpec extends RemoteCommunicationSpecTemplate("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote {
    transport = "akka.remote.actmote.ActorManagedRemoting"
    log-received-messages = on
    log-sent-messages = on
  }
  remote.netty {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /blub.remote = "akka://remote-sys@localhost:12346"
    /looker/child.remote = "akka://remote-sys@localhost:12346"
    /looker/child/grandchild.remote = "akka://RemoteCommunicationSpecTemplate@localhost:12345"
  }
}
""", """
akka.remote.netty.port=12346
akka.remote.transport = "akka.remote.netty.NettyRemoteTransport"
""") {

}
