package akka.typed

package object internal {
  /*
   * These are safe due to the self-type of ActorRef
   */
  implicit class ToImpl[U](val ref: ActorRef[U]) extends AnyVal {
    def sorry: ActorRefImpl[U] = ref.asInstanceOf[ActorRefImpl[U]]
  }
  // This one is necessary because Scala refuses to infer Nothing
  implicit class ToImplNothing(val ref: ActorRef[Nothing]) extends AnyVal {
    def sorryForNothing: ActorRefImpl[Nothing] = ref.asInstanceOf[ActorRefImpl[Nothing]]
  }
}
