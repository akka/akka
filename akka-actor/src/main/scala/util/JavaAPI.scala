package se.scalablesolutions.akka.util

object JavaAPI {
  /** A Function interface
   * Used to create first-class-functions is Java (sort of)
   * Java API
   */
  trait Function[T,R] {
    def apply(param: T): R
  }

  /** A Procedure is like a Function, but it doesn't produce a return value
   * Java API
   */
  trait Procedure[T] {
    def apply(param: T): Unit
  }

  /**
   * An executable piece of code that takes no parameters and doesn't return any value
   */
  trait SideEffect {
    def apply: Unit
  }

	/**
   * This class represents optional values. Instances of <code>Option</code>
	 * are either instances of case class <code>Some</code> or it is case
	 * object <code>None</code>.
   * <p>
   * Java API
   */
  sealed abstract class Option[A] extends java.lang.Iterable[A] {
    def get: A
    def isDefined: Boolean
    def asScala: scala.Option[A]
  }

  /**
   * Class <code>Some[A]</code> represents existing values of type
   * <code>A</code>.
   * <p>
   * Java API
   */
  final case class Some[A](v: A) extends Option[A] {
    import scala.collection.JavaConversions._

    def get = v
    def iterator = Iterator.single(v)
    def isDefined = true
    def asScala = scala.Some(v)
  }

  /**
   * This case object represents non-existent values.
   * <p>
   * Java API
   */
  case class None[A]() extends Option[A] {
    import scala.collection.JavaConversions._

    def get = throw new NoSuchElementException("None.get")
    def iterator = Iterator.empty
    def isDefined = false
    def asScala = scala.None
  }

  def some[A](v: A) = Some(v)
  def none[A] = None[A]

  implicit def java2ScalaOption[A](o: Option[A]): scala.Option[A] = o.asScala
  implicit def scala2JavaOption[A](o: scala.Option[A]): Option[A] =
    if (o.isDefined) Some(o.get) else None[A]
}

