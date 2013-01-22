package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import akka.actor.Props

object CreateChild {
  import Helpers._

  def impl[C <: ChannelList: c.WeakTypeTag, Pa <: ChannelList: c.WeakTypeTag, Ch <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Channels[_, C]
    })(factory: c.Expr[Channels[Pa, Ch]]): c.Expr[ChannelRef[Ch]] = {

    import c.universe._
    if (weakTypeOf[Pa] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "Parent argument must not be Nothing")
    }
    if (weakTypeOf[Ch] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "channel list must not be Nothing")
    }
    val missing = missingChannels(c.universe)(weakTypeOf[C], inputChannels(c.universe)(weakTypeOf[Pa]))
    if (missing.isEmpty) {
      implicit val t = c.TypeTag[Ch](c.weakTypeOf[Ch])
      reify(new ChannelRef[Ch](c.prefix.splice.context.actorOf(Props(factory.splice))))
    } else {
      c.error(c.enclosingPosition, s"This actor cannot support a child requiring channels ${missing mkString ", "}")
      reify(???)
    }
  }

}