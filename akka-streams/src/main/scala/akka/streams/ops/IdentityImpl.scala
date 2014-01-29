package akka.streams
package ops

object IdentityImpl {
  def apply[I](id: Identity[I]): OpInstance[I, I] =
    new OpInstance[I, I] {
      def handle(result: SimpleResult[I]): Result[I] = result
    }
}
