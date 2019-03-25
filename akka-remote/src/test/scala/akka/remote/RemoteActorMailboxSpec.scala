/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.actor.ActorMailboxSpec
import com.typesafe.config.ConfigFactory

class RemoteActorMailboxSpec
    extends ActorMailboxSpec(
      ConfigFactory.parseString("""akka.actor.provider = remote""").withFallback(ActorMailboxSpec.mailboxConf)) {}
