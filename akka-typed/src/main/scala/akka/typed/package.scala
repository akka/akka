package akka

package object typed {
  import internal._

  /*
   * These are safe due to the self-type of ActorRef
   */
  private[typed] implicit class ToImpl[U](val ref: ActorRef[U]) extends AnyVal {
    def sorry: ActorRefImpl[U] = ref.asInstanceOf[ActorRefImpl[U]]
  }
  // This one is necessary because Scala refuses to infer Nothing
  private[typed] implicit class ToImplNothing(val ref: ActorRef[Nothing]) extends AnyVal {
    def sorryForNothing: ActorRefImpl[Nothing] = ref.asInstanceOf[ActorRefImpl[Nothing]]
  }

  private[typed] implicit class ToProcessImpl[U](val p: Sessions.Process[U]) extends AnyVal {
    def sorry: ProcessImpl[U] = p.asInstanceOf[ProcessImpl[U]]
  }
  private[typed] implicit class ToProcessImplNothing(val p: Sessions.Process[Nothing]) extends AnyVal {
    def sorryForNothing: ProcessImpl[Nothing] = p.asInstanceOf[ProcessImpl[Nothing]]
  }

  private[typed] implicit class ToChannelImpl[U](val p: Sessions.Channel[U]) extends AnyVal {
    def sorry: ChannelImpl[U] = p.asInstanceOf[ChannelImpl[U]]
  }
  private[typed] implicit class ToChannelImplNothing(val p: Sessions.Channel[Nothing]) extends AnyVal {
    def sorryForNothing: ChannelImpl[Nothing] = p.asInstanceOf[ChannelImpl[Nothing]]
  }
}
