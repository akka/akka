/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe

object Narrow {
  import Helpers._

  def impl[Desired <: ChannelList: c.WeakTypeTag, MyChannels <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = ChannelRef[MyChannels]
    }): c.Expr[ChannelRef[Desired]] = {
    import c.{ universe ⇒ u }
    narrowCheck(u)(u.weakTypeOf[MyChannels], u.weakTypeOf[Desired]) match {
      case Nil        ⇒ // okay
      case err :: Nil ⇒ c.error(c.enclosingPosition, err)
      case list       ⇒ c.error(c.enclosingPosition, list mkString ("multiple errors:\n  - ", "\n  - ", ""))
    }
    u.reify(c.prefix.splice.asInstanceOf[ChannelRef[Desired]])
  }
}