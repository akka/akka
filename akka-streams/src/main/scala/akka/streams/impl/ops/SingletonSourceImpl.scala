package akka.streams.impl.ops

import akka.streams.impl.{ Continue, Downstream, DynamicSyncSource, Effect }

class SingletonSourceImpl[O](downstream: Downstream[O], element: O) extends DynamicSyncSource {
  def initial = WaitingForRequest

  def WaitingForRequest = new State {
    def handleRequestMore(n: Int): Effect = {
      become(Completed)
      downstream.next(element) ~ downstream.complete
    }
    def handleCancel(): Effect = {
      become(Completed)
      Continue
    }
  }
  def Completed = new State {
    def handleRequestMore(n: Int): Effect = Continue
    def handleCancel(): Effect = Continue
  }
}
