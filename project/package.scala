package object akka {

  implicit class RichBoolean(val b: Boolean) extends AnyVal {
    final def apply[A](a: => A): Option[A] = if (b) Some(a) else None
  }

}
