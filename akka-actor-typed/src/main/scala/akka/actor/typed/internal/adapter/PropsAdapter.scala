/*
 * Copyright (C) 2017-2022 Lightbend Inc. <https://www.lightbend.com>
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
  def apply[T](behavior: () => Behavior[T], props: Props, rethrowTypedFailure: Boolean): akka.actor.Props = {
    val classicProps = akka.actor.Props(new ActorAdapter(behavior(), rethrowTypedFailure))

    val dispatcherProps = (props.firstOrElse[DispatcherSelector](DispatcherDefault.empty) match {
      case _: DispatcherDefault          => classicProps
      case DispatcherFromConfig(name, _) => classicProps.withDispatcher(name)
      case _: DispatcherSameAsParent     => classicProps.withDispatcher(Deploy.DispatcherSameAsParent)
      case unknown                       => throw new RuntimeException(s"Unsupported dispatcher selector: $unknown")
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors

    val mailboxProps = props.firstOrElse[MailboxSelector](MailboxSelector.default()) match {
      case _: DefaultMailboxSelector           => dispatcherProps
      case BoundedMailboxSelector(capacity, _) =>
        // specific support in classic Mailboxes
        dispatcherProps.withMailbox(s"${Mailboxes.BoundedCapacityPrefix}$capacity")
      case MailboxFromConfigSelector(path, _) =>
        dispatcherProps.withMailbox(path)
      case unknown => throw new RuntimeException(s"Unsupported mailbox selector: $unknown")
    }

    val localDeploy = mailboxProps.withDeploy(Deploy.local) // disallow remote deployment for typed actors

    val tags = props.firstOrElse[ActorTags](ActorTagsImpl.empty).tags
    if (tags.isEmpty) localDeploy
    else localDeploy.withActorTags(tags)
  }

}
