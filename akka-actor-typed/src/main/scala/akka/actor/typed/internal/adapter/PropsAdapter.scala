/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.Deploy
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.MailboxSelector
import akka.actor.typed.Props
import akka.actor.typed.internal.PropsImpl._
import akka.annotation.InternalApi
import akka.dispatch.Mailboxes

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](
      behavior: () => Behavior[T],
      deploy: Props = Props.empty,
      rethrowTypedFailure: Boolean = true): akka.actor.Props = {
    val props = akka.actor.Props(new ActorAdapter(behavior(), rethrowTypedFailure))

    val p1 = (deploy.firstOrElse[DispatcherSelector](DispatcherDefault.empty) match {
      case _: DispatcherDefault          => props
      case DispatcherFromConfig(name, _) => props.withDispatcher(name)
      case _: DispatcherSameAsParent     => props.withDispatcher(Deploy.DispatcherSameAsParent)
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors

    val p2 = deploy.firstOrElse[MailboxSelector](MailboxSelector.default()) match {
      case _: DefaultMailboxSelector           => p1
      case BoundedMailboxSelector(capacity, _) =>
        // specific support in classic Mailboxes
        p1.withMailbox(s"${Mailboxes.BoundedCapacityPrefix}$capacity")
      case MailboxFromConfigSelector(path, _) =>
        props.withMailbox(path)
    }

    p2.withDeploy(Deploy.local) // disallow remote deployment for typed actors
  }

}
