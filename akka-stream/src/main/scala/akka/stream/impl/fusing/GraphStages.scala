/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream._
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }

/**
 * INTERNAL API
 */
object GraphStages {

  class Identity[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Outlet[T]("out")

    val shape = FlowShape(in, out)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = completeStage()
        override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
        override def onDownstreamFinish(): Unit = completeStage()
      })
    }

    override def toString = "Identity"
  }

  class Detacher[T] extends GraphStage[FlowShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Outlet[T]("out")
    val shape = FlowShape(in, out)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      var initialized = false

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          if (isAvailable(out)) {
            push(out, grab(in))
            pull(in)
          }
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (!initialized) {
            pull(in)
            initialized = true
          } else if (isAvailable(in)) {
            push(out, grab(in))
            if (!hasBeenPulled(in)) pull(in)
          }
        }
      })

    }

    override def toString = "Detacher"
  }

  class Broadcast[T](private val outCount: Int) extends GraphStage[UniformFanOutShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Vector.fill(outCount)(Outlet[T]("out"))
    val shape = UniformFanOutShape(in, out: _*)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      private var pending = outCount

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          pending = outCount
          val elem = grab(in)
          out.foreach(push(_, elem))
        }
      })

      val outHandler = new OutHandler {
        override def onPull(): Unit = {
          pending -= 1
          if (pending == 0) pull(in)
        }
      }

      out.foreach(setHandler(_, outHandler))
    }

    override def toString = "Broadcast"

  }

  class Zip[A, B] extends GraphStage[FanInShape2[A, B, (A, B)]] {
    val in0 = Inlet[A]("in0")
    val in1 = Inlet[B]("in1")
    val out = Outlet[(A, B)]("out")
    val shape = new FanInShape2[A, B, (A, B)](in0, in1, out)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      var pending = 2

      val inHandler = new InHandler {
        override def onPush(): Unit = {
          pending -= 1
          if (pending == 0) push(out, (grab(in0), grab(in1)))
        }
      }

      setHandler(in0, inHandler)
      setHandler(in1, inHandler)
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pending = 2
          pull(in0)
          pull(in1)
        }
      })
    }

    override def toString = "Zip"
  }

  class Merge[T](private val inCount: Int) extends GraphStage[UniformFanInShape[T, T]] {
    val in = Vector.fill(inCount)(Inlet[T]("in"))
    val out = Outlet[T]("out")
    val shape = UniformFanInShape(out, in: _*)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      private var initialized = false

      private val pendingQueue = Array.ofDim[Inlet[T]](inCount)
      private var pendingHead: Int = 0
      private var pendingTail: Int = 0

      private def noPending: Boolean = pendingHead == pendingTail
      private def enqueue(in: Inlet[T]): Unit = {
        pendingQueue(pendingTail % inCount) = in
        pendingTail += 1
      }
      private def dequeueAndDispatch(): Unit = {
        val in = pendingQueue(pendingHead % inCount)
        pendingHead += 1
        push(out, grab(in))
        pull(in)
      }

      in.foreach { i ⇒
        setHandler(i, new InHandler {
          override def onPush(): Unit = {
            if (isAvailable(out)) {
              if (noPending) {
                push(out, grab(i))
                pull(i)
              } else {
                enqueue(i)
                dequeueAndDispatch()
              }
            } else enqueue(i)
          }
        })
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (!initialized) {
            initialized = true
            in.foreach(pull(_))
          } else {
            if (!noPending) {
              dequeueAndDispatch()
            }
          }
        }
      })
    }

    override def toString = "Merge"
  }

  class Balance[T](private val outCount: Int) extends GraphStage[UniformFanOutShape[T, T]] {
    val in = Inlet[T]("in")
    val out = Vector.fill(outCount)(Outlet[T]("out"))
    val shape = UniformFanOutShape[T, T](in, out: _*)

    override def createLogic: GraphStageLogic = new GraphStageLogic {
      private val pendingQueue = Array.ofDim[Outlet[T]](outCount)
      private var pendingHead: Int = 0
      private var pendingTail: Int = 0

      private def noPending: Boolean = pendingHead == pendingTail
      private def enqueue(out: Outlet[T]): Unit = {
        pendingQueue(pendingTail % outCount) = out
        pendingTail += 1
      }
      private def dequeueAndDispatch(): Unit = {
        val out = pendingQueue(pendingHead % outCount)
        pendingHead += 1
        push(out, grab(in))
        if (!noPending) pull(in)
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = dequeueAndDispatch()
      })

      out.foreach { o ⇒
        setHandler(o, new OutHandler {
          override def onPull(): Unit = {
            if (isAvailable(in)) {
              if (noPending) {
                push(o, grab(in))
              } else {
                enqueue(o)
                dequeueAndDispatch()
              }
            } else {
              if (!hasBeenPulled(in)) pull(in)
              enqueue(o)
            }
          }
        })
      }
    }

    override def toString = "Balance"
  }

}
