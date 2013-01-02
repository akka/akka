/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

package object channels {
  sealed trait ChannelList
  sealed trait TNil extends ChannelList
  sealed trait :=:[A <: Tuple2[_, _], B <: ChannelList] extends ChannelList
}
