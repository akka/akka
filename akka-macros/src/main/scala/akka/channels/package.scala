/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object channels {
  trait Channel[I, O]

  sealed trait ChannelList
  sealed trait TNil extends ChannelList
  sealed trait :=:[A <: Channel[_, _], B <: ChannelList] extends ChannelList
}