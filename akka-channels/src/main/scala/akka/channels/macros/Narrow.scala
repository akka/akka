package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe

object Narrow {
  import Helpers._

  def impl[C <: ChannelList: c.WeakTypeTag, T <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = ChannelRef[T]
    }): c.Expr[ChannelRef[C]] = {
    import c.{ universe ⇒ u }
    narrowCheck(u)(u.weakTypeOf[T], u.weakTypeOf[C]) match {
      case Nil        ⇒ // okay
      case err :: Nil ⇒ c.error(c.enclosingPosition, err)
      case list       ⇒ c.error(c.enclosingPosition, list mkString ("multiple errors:\n  - ", "\n  - ", ""))
    }
    u.reify(c.prefix.splice.asInstanceOf[ChannelRef[C]])
  }
}