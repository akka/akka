package sample.remote.calculator

trait MathOp

final case class Add(nbr1: Int, nbr2: Int) extends MathOp

final case class Subtract(nbr1: Int, nbr2: Int) extends MathOp

final case class Multiply(nbr1: Int, nbr2: Int) extends MathOp

final case class Divide(nbr1: Double, nbr2: Int) extends MathOp

trait MathResult

final case class AddResult(nbr: Int, nbr2: Int, result: Int) extends MathResult

final case class SubtractResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

final case class MultiplicationResult(nbr1: Int, nbr2: Int, result: Int) extends MathResult

final case class DivisionResult(nbr1: Double, nbr2: Int, result: Double) extends MathResult

