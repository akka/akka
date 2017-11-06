package akka.persistence.testkit

trait RejectionPolicy {

  def rejectOrPass(msg: Any): Rejection

}

sealed trait Rejection

case object PassMessage extends Rejection

case class Reject(e: Throwable) extends Rejection

class RejectionDecider(var policy: RejectionPolicy)
