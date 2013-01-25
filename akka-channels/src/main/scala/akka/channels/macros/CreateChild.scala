package akka.channels.macros

import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import akka.actor.Props

object CreateChild {
  import Helpers._

  def impl[MyChannels <: ChannelList: c.WeakTypeTag, ParentChannels <: ChannelList: c.WeakTypeTag, ChildChannels <: ChannelList: c.WeakTypeTag](
    c: Context {
      type PrefixType = Channels[_, MyChannels]
    })(factory: c.Expr[Channels[ParentChannels, ChildChannels]]): c.Expr[ChannelRef[ChildChannels]] = {

    import c.universe._

    if (weakTypeOf[ParentChannels] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "Parent argument must not be Nothing")
    }
    if (weakTypeOf[ChildChannels] =:= weakTypeOf[Nothing]) {
      c.abort(c.enclosingPosition, "channel list must not be Nothing")
    }

    val missing = missingChannels(c.universe)(weakTypeOf[MyChannels], inputChannels(c.universe)(weakTypeOf[ParentChannels]))
    if (missing.isEmpty) {
      implicit val t = c.TypeTag[ChildChannels](c.weakTypeOf[ChildChannels])
      reify(new ChannelRef[ChildChannels](c.prefix.splice.context.actorOf(Props(factory.splice))))
    } else {
      c.abort(c.enclosingPosition, s"This actor cannot support a child requiring channels ${missing mkString ", "}")
    }
  }

}