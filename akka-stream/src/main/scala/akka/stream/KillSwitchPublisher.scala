/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import org.reactivestreams.Publisher

trait KillSwitchPublisher[T] extends Publisher[T] with KillSwitch
