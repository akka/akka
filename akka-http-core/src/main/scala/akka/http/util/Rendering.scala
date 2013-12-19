package akka.http.util

import scala.annotation.tailrec
import scala.collection.LinearSeq
import akka.http.model.HttpData
import akka.http.util._

/** An entity that can render itself */
trait Renderable {
  def render[R <: Rendering](r: R): r.type
}

/** An entity that can render itself and implements toString in terms of its rendering */
trait ToStringRenderable extends Renderable {
  override def toString = render(new StringRendering).get
}

/** An entity that has a rendered value (like an HttpHeader) */
trait ValueRenderable extends ToStringRenderable {
  def value: String = toString
}

/**
 * An entity whose rendering result is cached in an unsynchronized and non-volatile lazy.
 */
trait LazyValueBytesRenderable extends Renderable {
  // unsynchronized and non-volatile lazy init, worst case: we init once per core
  // which, since instances of derived classes are usually long-lived, is still better
  // that a synchronization overhead or even @volatile reads
  private[this] var _valueBytes: Array[Byte] = _
  private def valueBytes =
    if (_valueBytes != null) _valueBytes else { _valueBytes = value.getAsciiBytes; _valueBytes }

  def value: String
  def render[R <: Rendering](r: R): r.type = r ~~ valueBytes
  override def toString = value
}

/**
 * An entity whose rendering result is determined eagerly at instantiation (and then is cached).
 * Useful for common predefined singleton values.
 */
trait SingletonValueRenderable extends Product with Renderable {
  private[this] val valueBytes = value.getAsciiBytes
  def value = productPrefix
  def render[R <: Rendering](r: R): r.type = r ~~ valueBytes
}

/**
 * A typeclass for rendering values.
 */
trait Renderer[-T] {
  def render[R <: Rendering](r: R, value: T): r.type
}

object Renderer {
  implicit object CharRenderer extends Renderer[Char] {
    def render[R <: Rendering](r: R, value: Char): r.type = r ~~ value
  }
  implicit object StringRenderer extends Renderer[String] {
    def render[R <: Rendering](r: R, value: String): r.type = r ~~ value
  }
  implicit object HttpDataRenderer extends Renderer[HttpData] {
    def render[R <: Rendering](r: R, value: HttpData): r.type = r ~~ value
  }
  implicit object CharsRenderer extends Renderer[Array[Char]] {
    def render[R <: Rendering](r: R, value: Array[Char]): r.type = r ~~ value
  }
  object RenderableRenderer extends Renderer[Renderable] {
    def render[R <: Rendering](r: R, value: Renderable): r.type = value.render(r)
  }
  implicit def renderableRenderer[T <: Renderable]: Renderer[T] = RenderableRenderer

  def optionRenderer[D, T](defaultValue: D)(implicit sRenderer: Renderer[D], tRenderer: Renderer[T]) =
    new Renderer[Option[T]] {
      def render[R <: Rendering](r: R, value: Option[T]): r.type =
        if (value.isEmpty) sRenderer.render(r, defaultValue) else tRenderer.render(r, value.get)
    }

  def defaultSeqRenderer[T: Renderer] = genericSeqRenderer[Renderable, T](Rendering.`, `, Rendering.Empty)
  def seqRenderer[T: Renderer](separator: String = ", ", empty: String = "") = genericSeqRenderer[String, T](separator, empty)
  def genericSeqRenderer[S, T](separator: S, empty: S)(implicit sRenderer: Renderer[S], tRenderer: Renderer[T]) =
    new Renderer[Seq[T]] {
      def render[R <: Rendering](r: R, value: Seq[T]): r.type = {
        @tailrec def recI(values: IndexedSeq[T], ix: Int = 0): r.type =
          if (ix < values.size) {
            if (ix > 0) sRenderer.render(r, separator)
            tRenderer.render(r, values(ix))
            recI(values, ix + 1)
          } else r

        @tailrec def recL(remaining: LinearSeq[T]): r.type =
          if (remaining.nonEmpty) {
            if (remaining ne value) sRenderer.render(r, separator)
            tRenderer.render(r, remaining.head)
            recL(remaining.tail)
          } else r

        value match {
          case Nil              ⇒ r ~~ empty
          case x: IndexedSeq[T] ⇒ recI(x)
          case x: LinearSeq[T]  ⇒ recL(x)
          case x                ⇒ sys.error("Unsupported Seq type: " + x)
        }
      }
    }
}

/**
 * The interface for a rendering sink. May be implemented with different backing stores.
 */
trait Rendering {
  def ~~(char: Char): this.type
  def ~~(bytes: Array[Byte]): this.type
  def ~~(data: HttpData): this.type

  def ~~(f: Float): this.type = this ~~ f.toString
  def ~~(d: Double): this.type = this ~~ d.toString

  def ~~(int: Int): this.type = this ~~ int.toLong

  def ~~(long: Long): this.type =
    if (long != 0) {
      val value = if (long < 0) { this ~~ '-'; -long } else long
      @tailrec def magnitude(m: Long = 1): Long = if ((value / m) < 10) m else magnitude(m * 10)
      @tailrec def putNextChar(v: Long, m: Long): this.type =
        if (m > 0) {
          this ~~ ('0' + (v / m)).toChar
          putNextChar(v % m, m / 10)
        } else this
      putNextChar(value, magnitude())
    } else this ~~ '0'

  /**
   * Renders the given Int in (lower case) hex notation.
   */
  def ~~%(int: Int): this.type = this ~~% int.toLong

  /**
   * Renders the given Long in (lower case) hex notation.
   */
  def ~~%(long: Long): this.type =
    if (long != 0) {
      @tailrec def putChar(shift: Int = 60): this.type = {
        this ~~ CharUtils.lowerHexDigit(long >>> shift)
        if (shift > 0) putChar(shift - 4) else this
      }
      @tailrec def skipZeros(shift: Int = 60): this.type =
        if ((long >>> shift) > 0) putChar(shift) else skipZeros(shift - 4)
      skipZeros()
    } else this ~~ '0'

  def ~~(string: String): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < string.length) { this ~~ string.charAt(ix); rec(ix + 1) } else this
    rec()
  }

  def ~~(chars: Array[Char]): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < chars.length) { this ~~ chars(ix); rec(ix + 1) } else this
    rec()
  }

  def ~~[T](value: T)(implicit ev: Renderer[T]): this.type = ev.render(this, value)

  /**
   * Renders the given string either directly (if it only contains token chars)
   * or in double quotes (if it contains at least one non-token char).
   */
  def ~~#(s: String): this.type =
    if (CharPredicate.HttpToken.matchAll(s)) this ~~ s else ~~('"').putEscaped(s) ~~ '"'

  def putEscaped(s: String, escape: CharPredicate = Rendering.`\"`, escChar: Char = '\\'): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < s.length) {
        val c = s.charAt(ix)
        if (escape(c)) this ~~ escChar
        this ~~ c
        rec(ix + 1)
      } else this
    rec()
  }
}

object Rendering {
  val `\"` = CharPredicate('\\', '"')

  case object `, ` extends SingletonValueRenderable // default separator
  case object Empty extends Renderable {
    def render[R <: Rendering](r: R): r.type = r
  }

  case object CrLf extends Renderable {
    def render[R <: Rendering](r: R): r.type = r ~~ '\r' ~~ '\n'
  }
}

/** A rendering sink that renders into a String by appending to a StringBuilder. */
class StringRendering extends Rendering {
  private[this] val sb = new java.lang.StringBuilder
  def ~~(char: Char): this.type = { sb.append(char); this }
  def ~~(bytes: Array[Byte]): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < bytes.length) { this ~~ bytes(ix).asInstanceOf[Char]; rec(ix + 1) } else this
    rec()
  }
  def ~~(data: HttpData): this.type = this ~~ data.toByteArray
  def get: String = sb.toString
}

// FIXME: decide if we ByteStringRendering, ByteArrayRendering is still needed
// FIXME: resurrect HttpDataRendering
