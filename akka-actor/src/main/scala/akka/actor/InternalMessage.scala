/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.annotation.InternalApi

/**
 * INTERNAL API: marker trait for internal messages
 * for optimization purposes (see typed ActorAdapter)
 */
@InternalApi private[akka] trait InternalMessage
