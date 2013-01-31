package akka.channels.macros

import akka.AkkaException
import scala.util.control.NoStackTrace
import akka.channels._
import scala.reflect.runtime.{ universe ⇒ ru }
import ru.TypeTag
import scala.reflect.macros.Context
import scala.reflect.api.Universe
import scala.reflect.api.TypeCreator

object Helpers {

  type Recv[T, Ch <: ChannelList] = Function2[T, ChannelRef[Ch], Unit]

  case class CheckType[T](tt: TypeTag[T])
  case object CheckTypeACK
  case class CheckTypeNAK(errors: String)

  def error(c: Context, msg: String) = c.error(c.enclosingPosition, msg)
  def abort(c: Context, msg: String) = c.abort(c.enclosingPosition, msg)

  def imp[T: c.WeakTypeTag](c: Context): c.Expr[T] = {
    import c.universe._
    c.Expr[T](TypeApply(Ident("implicitly"), List(TypeTree().setType(weakTypeOf[T]))))
  }

  def bool(c: Context, b: Boolean): c.Expr[Boolean] = c.Expr[Boolean](c.universe.Literal(c.universe.Constant(b)))

  def checkUnique(u: Universe)(channel: u.Type, list: u.Type): Option[String] = {
    val channels = inputChannels(u)(list) groupBy (_.erasure)
    val dupes = channels.get(channel.erasure).getOrElse(Nil).filterNot(_ =:= channel)
    if (dupes.isEmpty) None
    else Some(s"erasure ${channel.erasure} overlaps with declared channels ${dupes mkString ", "}")
  }

  /**
   * check that the original ChannelList is a subtype of the target ChannelList; return a list or error strings
   */
  def narrowCheck(u: Universe)(orig: u.Type, target: u.Type): List[String] = {
    var errors = List.empty[String]
    for (in ← inputChannels(u)(target)) {
      val replies = replyChannels(u)(orig, in)
      if (replies.isEmpty) errors ::= s"original ChannelRef does not support input type $in"
      else {
        val targetReplies = replyChannels(u)(target, in)
        val unsatisfied = replies filterNot (r ⇒ targetReplies exists (r <:< _))
        if (unsatisfied.nonEmpty) errors ::= s"reply types ${unsatisfied mkString ", "} not covered for channel $in"
        val leftovers = targetReplies filterNot (t ⇒ replies exists (_ <:< t))
        if (leftovers.nonEmpty) errors ::= s"desired reply types ${leftovers mkString ", "} are superfluous for channel $in"
      }
    }
    errors.reverse
  }

  /**
   * get all input channels from a ChannelList or return the given type
   */
  final def inputChannels(u: Universe)(list: u.Type): List[u.Type] = {
    import u._
    val imp = u.mkImporter(ru)
    val tpeChannelList = imp.importType(ru.typeOf[ChannelList])
    val tpeTNil = imp.importType(ru.typeOf[TNil])
    def rec(l: u.Type, acc: List[u.Type]): List[u.Type] = l match {
      case TypeRef(_, _, TypeRef(_, _, in :: _) :: tail :: Nil) ⇒ rec(tail, if (acc contains in) acc else in :: acc)
      case TypeRef(_, _, ExistentialType(_, TypeRef(_, _, in :: _)) :: tail :: Nil) ⇒ rec(tail, if (acc contains in) acc else in :: acc)
      case ExistentialType(_, x) ⇒ rec(x, acc)
      case last ⇒ if (last =:= tpeTNil) acc.reverse else (last :: acc).reverse
    }
    if (list <:< tpeChannelList) rec(list, Nil)
    else List(list)
  }

  /**
   * find all input channels matching the given message type and return a
   * list of their respective reply channels
   */
  final def replyChannels(u: Universe)(list: u.Type, msg: u.Type): List[u.Type] = {
    import u._
    val imp = u.mkImporter(ru)
    val tpeReplyTypes = imp.importType(ru.typeOf[ReplyChannels[_]])
    val tpeTNil = imp.importType(ru.typeOf[TNil])
    val msgTypes = inputChannels(u)(msg)
    def rec(l: Type, acc: List[Type]): List[Type] = {
      l match {
        case TypeRef(_, _, TypeRef(_, _, in :: out :: Nil) :: tail :: Nil) if msgTypes exists (_ <:< in) ⇒
          rec(tail, if (acc contains out) acc else out :: acc)
        case TypeRef(_, _, _ :: tail :: Nil) ⇒
          rec(tail, acc)
        case x if x =:= tpeTNil       ⇒ acc.reverse
        case x if x <:< tpeReplyTypes ⇒ throw new IllegalArgumentException("cannot compute the ReplyChannels of a ReplyChannels type")
        case x @ TypeRef(NoPrefix, _, Nil) ⇒
          acc reverse_::: (if (msgTypes exists (_ <:< x)) appliedType(tpeReplyTypes.typeConstructor, List(x)) :: Nil else Nil)
        case x ⇒ throw new IllegalArgumentException(s"no idea what this type is: $x")
      }
    }
    val n = typeOf[Nothing]
    if (msg =:= n) List(n) else rec(list, Nil)
  }

  /**
   * filter from the `required` list of types all which are subtypes of inputs of the ChannelList
   */
  final def missingChannels(u: Universe)(channels: u.Type, required: List[u.Type]): List[u.Type] = {
    import u._
    // making the top-level method recursive blows up the compiler (when compiling the macro itself)
    def rec(ch: Type, req: List[Type]): List[Type] = {
      ch match {
        case TypeRef(_, _, TypeRef(_, _, in :: _) :: tail :: Nil) ⇒ rec(tail, req filterNot (_ <:< in))
        case last ⇒ req filterNot (_ <:< last)
      }
    }
    rec(channels, required)
  }

  /**
   * convert a list of types List(<T1>, <T2>, ...) into a ChannelList
   * ( Channel[<T1>, Nothing] :=: Channel[<T2>, Nothing] :=: ... :=: TNil )
   */
  final def toChannels(u: Universe)(list: List[u.Type], out: u.Type): u.Type = {
    import u._
    def rec(l: List[Type], acc: Type): Type = l match {
      case head :: (tail: List[Type]) ⇒
        if (head =:= weakTypeOf[Nothing]) rec(tail, acc)
        else
          rec(tail,
            appliedType(weakTypeOf[:+:[_, _]].typeConstructor, List(
              appliedType(weakTypeOf[Tuple2[_, _]].typeConstructor, List(
                head,
                out)),
              acc)))
      case _ ⇒ acc
    }
    rec(list.reverse, weakTypeOf[TNil])
  }

  /**
   * takes a message tpe and tree and returns an expression which yields the
   * underlying message (i.e. unwraps WrappedMessage if necessary)
   */
  final def toMsg(c: Context)(tree: c.Expr[Any], tpe: c.Type): c.Expr[Any] = {
    import c.universe._
    if (tpe <:< c.typeOf[WrappedMessage[_, _]])
      c.universe.reify(tree.splice.asInstanceOf[WrappedMessage[TNil, Any]].value)
    else tree
  }

  final def unwrapMsgType(u: Universe)(msg: u.Type): u.Type = {
    import u._
    if (msg <:< typeOf[WrappedMessage[_, _]])
      msg match {
        case TypeRef(_, _, x :: _) ⇒ x
      }
    else msg
  }

}