/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.Deploy
import akka.actor.typed.ActorTags
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

    val dispatcherProps = (deploy.firstOrElse[DispatcherSelector](DispatcherDefault.empty) match {
      case _: DispatcherDefault          => props
      case DispatcherFromConfig(name, _) => props.withDispatcher(name)
      case _: DispatcherSameAsParent     => props.withDispatcher(Deploy.DispatcherSameAsParent)
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors

    val mailboxProps = deploy.firstOrElse[MailboxSelector](MailboxSelector.default()) match {
      case _: DefaultMailboxSelector           => dispatcherProps
      case BoundedMailboxSelector(capacity, _) =>
        // specific support in classic Mailboxes
        dispatcherProps.withMailbox(s"${Mailboxes.BoundedCapacityPrefix}$capacity")
      case MailboxFromConfigSelector(path, _) =>
        dispatcherProps.withMailbox(path)
    }

    val localDeploy = mailboxProps.withDeploy(Deploy.local) // disallow remote deployment for typed actors

    val tags = deploy.firstOrElse[ActorTags](ActorTagsImpl.empty).tags
    if (tags.isEmpty) localDeploy
    else localDeploy.withActorTags(tags)
  }

}
