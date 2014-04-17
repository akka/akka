/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.annotation.tailrec
import scala.collection.LinearSeq
import org.parboiled2.{ CharPredicate, CharUtils }
import akka.http.model.parser.CharacterClasses
import akka.util.{ ByteStringBuilder, ByteString }

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
  implicit object ByteStringRenderer extends Renderer[ByteString] {
    def render[R <: Rendering](r: R, value: ByteString): r.type = r ~~ value
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
  def ~~(ch: Char): this.type
  def ~~(bytes: Array[Byte]): this.type
  def ~~(bytes: ByteString): this.type

  def ~~(f: Float): this.type = this ~~ f.toString
  def ~~(d: Double): this.type = this ~~ d.toString

  def ~~(i: Int): this.type = this ~~ i.toLong

  def ~~(lng: Long): this.type =
    if (lng != 0) {
      val value = if (lng < 0) { this ~~ '-'; -lng } else lng
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
  def ~~%(i: Int): this.type = this ~~% i.toLong

  /**
   * Renders the given Long in (lower case) hex notation.
   */
  def ~~%(lng: Long): this.type =
    if (lng != 0) {
      @tailrec def putChar(shift: Int = 60): this.type = {
        this ~~ CharUtils.lowerHexDigit(lng >>> shift)
        if (shift > 0) putChar(shift - 4) else this
      }
      @tailrec def skipZeros(shift: Int = 60): this.type =
        if ((lng >>> shift) > 0) putChar(shift) else skipZeros(shift - 4)
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
    if (CharacterClasses.tchar matchesAll s) this ~~ s else ~~#!(s)

  /**
   * Renders the given string in double quotes.
   */
  def ~~#!(s: String): this.type = ~~('"').putEscaped(s) ~~ '"'

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
  def ~~(ch: Char): this.type = { sb.append(ch); this }
  def ~~(bytes: Array[Byte]): this.type = {
    @tailrec def rec(ix: Int = 0): this.type =
      if (ix < bytes.length) { this ~~ bytes(ix).asInstanceOf[Char]; rec(ix + 1) } else this
    rec()
  }
  def ~~(bytes: ByteString): this.type = this ~~ bytes.toArray[Byte]
  def get: String = sb.toString
}

class ByteArrayRendering(sizeHint: Int) extends Rendering {
  private[this] var array = new Array[Byte](sizeHint)
  private[this] var size = 0

  def get: Array[Byte] =
    if (size == array.length) array
    else java.util.Arrays.copyOfRange(array, 0, size)

  def ~~(char: Char): this.type = {
    val oldSize = growBy(1)
    array(oldSize) = char.toByte
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      System.arraycopy(bytes, 0, array, oldSize, bytes.length)
    }
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) {
      val oldSize = growBy(bytes.length)
      bytes.copyToArray(array, oldSize, bytes.length)
    }
    this
  }

  private def growBy(delta: Int): Int = {
    val oldSize = size
    val neededSize = oldSize.toLong + delta
    if (array.length < neededSize)
      if (neededSize < Int.MaxValue) {
        val newLen = math.min(math.max(array.length.toLong << 1, neededSize), Int.MaxValue).toInt
        val newArray = new Array[Byte](newLen)
        System.arraycopy(array, 0, newArray, 0, array.length)
        array = newArray
      } else sys.error("Cannot create byte array greater than 2GB in size")
    size = neededSize.toInt
    oldSize
  }
}

class ByteStringRendering(sizeHint: Int) extends Rendering {
  private[this] val builder = new ByteStringBuilder
  builder.sizeHint(sizeHint)

  def get: ByteString = builder.result

  def ~~(char: Char): this.type = {
    builder += char.toByte
    this
  }

  def ~~(bytes: Array[Byte]): this.type = {
    if (bytes.length > 0) builder.putByteArrayUnsafe(bytes)
    this
  }

  def ~~(bytes: ByteString): this.type = {
    if (bytes.length > 0) builder ++= bytes
    this
  }
}