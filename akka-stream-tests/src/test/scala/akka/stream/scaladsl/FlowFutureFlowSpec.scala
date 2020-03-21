/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.NeverMaterializedException
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit.assertAllStagesStopped

import scala.concurrent.{ Future, Promise }

class FlowFutureFlowSpec extends StreamSpec {
  def src10(i: Int = 0) = Source(i until (i + 10))
  def src10WithFailure(i: Int = 0)(failOn: Int) = src10(i).map {
    case `failOn` => throw TE(s"fail on $failOn")
    case x        => x
  }

  "a futureFlow" must {
    "work in the simple case with a completed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow {
            Future.successful(Flow[Int])
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(0 until 10)
    }

    "work in the simple case with a late future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success(Flow[Int])

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(0 until 10)
    }

    "fail properly when future is a completed failed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow {
            Future.failed[Flow[Int, Int, NotUsed]](TE("damn!"))
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.failed.futureValue should be(a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal(TE("damn!"))

      fSeq.failed.futureValue should equal(TE("damn!"))

    }

    "fail properly when future is late completed failed future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.failure(TE("damn!"))

      fNotUsed.failed.futureValue should be(a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal(TE("damn!"))

      fSeq.failed.futureValue should equal(TE("damn!"))

    }

    "handle upstream failure when future is pre-completed" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10WithFailure()(5)
        .viaMat {
          Flow.futureFlow {
            Future.successful {
              Flow[Int].recover {
                case TE("fail on 5") => 99
              }
            }
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(List(0, 1, 2, 3, 4, 99))
    }

    "handle upstream failure when future is late-completed" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10WithFailure()(5)
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success {
        Flow[Int].recover {
          case TE("fail on 5") => 99
        }
      }

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(List(0, 1, 2, 3, 4, 99))
    }

    "propagate upstream failure when future is pre-completed" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10WithFailure()(5)
        .viaMat {
          Flow.futureFlow {
            Future.successful {
              Flow[Int]
            }
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.failed.futureValue should equal(TE("fail on 5"))
    }

    "propagate upstream failure when future is late-completed" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10WithFailure()(5)
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success {
        Flow[Int]
      }

      fNotUsed.futureValue should be(NotUsed)
      fSeq.failed.futureValue should equal(TE("fail on 5"))
    }

    "handle early upstream error when flow future is pre-completed" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = Source
        .failed(TE("not today my friend"))
        .viaMat {
          Flow.futureFlow {
            Future.successful {
              Flow[Int]
                .recover {
                  case TE("not today my friend") => 99
                }
                .concat(src10())
            }
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(99 +: (0 until 10))

    }

    "handle early upstream error when flow future is late-completed" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = Source
        .failed(TE("not today my friend"))
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success {
        Flow[Int]
          .recover {
            case TE("not today my friend") => 99
          }
          .concat(src10())
      }

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(99 +: (0 until 10))

    }

    "handle closed downstream when flow future is pre completed" in assertAllStagesStopped {
      val (fSeq1, fSeq2) = src10()
        .viaMat {
          Flow.futureFlow {
            Future.successful {
              Flow[Int].alsoToMat(Sink.seq)(Keep.right)
            }
          }
        }(Keep.right)
        .mapMaterializedValue(_.flatten)
        .take(0)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fSeq1.futureValue should be(empty)
      fSeq2.futureValue should be(empty)

    }

    "handle closed downstream when flow future is late completed" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, Future[collection.immutable.Seq[Int]]]]
      val (fSeq1, fSeq2) = src10()
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .mapMaterializedValue(_.flatten)
        .take(0)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fSeq1.value should be(empty)
      fSeq2.value should be(empty)

      prFlow.success {
        Flow[Int].alsoToMat(Sink.seq)(Keep.right)
      }

      fSeq1.futureValue should be(empty)
      fSeq2.futureValue should be(empty)
    }

    "handle early upstream completion when flow future is pre-completed" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = Source
        .empty[Int]
        .viaMat {
          Flow.futureFlow {
            Future.successful {
              Flow[Int].orElse(Source.single(99))
            }
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(99 :: Nil)
    }

    "handle early upstream completion when flow future is late-completed" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = Source
        .empty[Int]
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success {
        Flow[Int].orElse(Source.single(99))
      }

      fNotUsed.futureValue should be(NotUsed)
      fSeq.futureValue should equal(99 :: Nil)
    }

    "fails properly on materialization failure with a completed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow {
            Future.successful(Flow[Int].mapMaterializedValue[NotUsed](_ => throw TE("BBOM!")))
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.failed.futureValue should be(a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal(TE("BBOM!"))
      fSeq.failed.futureValue should equal(TE("BBOM!"))
    }

    "fails properly on materialization failure with a late future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success(Flow[Int].mapMaterializedValue[NotUsed](_ => throw TE("BBOM!")))

      fNotUsed.failed.futureValue should be(a[NeverMaterializedException])
      fNotUsed.failed.futureValue.getCause should equal(TE("BBOM!"))
      fSeq.failed.futureValue should equal(TE("BBOM!"))
    }

    "propagate flow failures with a completed future" in assertAllStagesStopped {
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow {
            Future.successful{
              Flow[Int]
                .map{
                  case 5 => throw TE("fail on 5")
                  case x => x
                }
            }
          }
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.futureValue should be(NotUsed)
      fSeq.failed.futureValue should equal(TE("fail on 5"))
    }

    "propagate flow failures with a late future" in assertAllStagesStopped {
      val prFlow = Promise[Flow[Int, Int, NotUsed]]
      val (fNotUsed, fSeq) = src10()
        .viaMat {
          Flow.futureFlow(prFlow.future)
        }(Keep.right)
        .toMat(Sink.seq)(Keep.both)
        .run()

      fNotUsed.value should be(empty)
      fSeq.value should be(empty)

      prFlow.success{
        Flow[Int]
          .map{
            case 5 => throw TE("fail on 5")
            case x => x
          }
      }

      fNotUsed.futureValue should be(NotUsed)
      fSeq.failed.futureValue should equal(TE("fail on 5"))
    }

  }

}
