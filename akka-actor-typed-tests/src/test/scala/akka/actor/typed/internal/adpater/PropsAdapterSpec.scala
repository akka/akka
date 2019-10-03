/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adpater

import akka.actor
import akka.actor.typed.Props
import akka.actor.typed.internal.adapter.PropsAdapter
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.Matchers
import org.scalatest.WordSpec

class PropsAdapterSpec extends WordSpec with Matchers {

  "PropsAdapter" should {
    "default to akka.dispatch.SingleConsumerOnlyUnboundedMailbox" in {
      val props: Props = Props.empty
      val pa: actor.Props = PropsAdapter(() => Behaviors.empty, props)
      pa.mailbox shouldEqual "akka.actor.typed.default-mailbox"
    }
  }
}
