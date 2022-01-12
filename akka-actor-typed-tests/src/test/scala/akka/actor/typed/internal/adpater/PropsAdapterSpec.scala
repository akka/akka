/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adpater

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import akka.actor
import akka.actor.typed.Props
import akka.actor.typed.internal.adapter.PropsAdapter
import akka.actor.typed.scaladsl.Behaviors

class PropsAdapterSpec extends AnyWordSpec with Matchers {

  "PropsAdapter" should {
    "default to akka.dispatch.SingleConsumerOnlyUnboundedMailbox" in {
      val props: Props = Props.empty
      val pa: actor.Props = PropsAdapter(() => Behaviors.empty, props, rethrowTypedFailure = false)
      pa.mailbox shouldEqual "akka.actor.typed.default-mailbox"
    }
  }
}
