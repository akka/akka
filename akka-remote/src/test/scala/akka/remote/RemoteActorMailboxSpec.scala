package akka.remote

import akka.actor.ActorMailboxSpec
import com.typesafe.config.ConfigFactory

class RemoteActorMailboxSpec extends ActorMailboxSpec(
  ConfigFactory.parseString("""akka.actor.provider = "akka.remote.RemoteActorRefProvider"""").
    withFallback(ActorMailboxSpec.mailboxConf)) {

}