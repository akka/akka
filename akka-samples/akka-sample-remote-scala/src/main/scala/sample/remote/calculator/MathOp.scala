package sample.remote.calculator

trait MathOp

case class Add(nbr1: Int, nbr2: Int) extends MathOp

case class Subtract(nbr1: Int, nbr2: Int) extends MathOp

case class Multiply(nbr1: Int, nbr2: Int) extends MathOp

case class Divide(nbr1: Double, nbr2: Int) extends MathOp

trait MathResult

case class AddResult(nbr: Int, nbr2: Int, result: Int) extends MathResult

case class SubtractResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

case class MultiplicationResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

case class DivisionResult(nbr1: Double, nbr2: Int, result: Double) extends MathResult

