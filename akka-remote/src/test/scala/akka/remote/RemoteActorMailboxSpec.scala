package akka.remote

import akka.actor.ActorMailboxSpec
import com.typesafe.config.ConfigFactory

class RemoteActorMailboxSpec extends ActorMailboxSpec(
  ConfigFactory.parseString("""akka.actor.provider = remote""").
    withFallback(ActorMailboxSpec.mailboxConf)) {

}