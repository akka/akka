package akka.stream.dsl

final case class Merge[T, U, V >: T with U]() {
  val in1 = new Output[T] {}
  val in2 = new Output[U] {}
  val out = new Input[V] {}
}

final case class Zip[T, U]() {
  val in1 = new Output[T] {}
  val in2 = new Output[U] {}
  val out = new Input[(T, U)] {}
}

final case class Concat[T, U, V >: T with U]() {
  val in1 = new Output[T] {}
  val in2 = new Output[U] {}
  val out = new Input[V] {}
}

final case class Broadcast[T]() {
  val in = new Output[T] {}
  val out1 = new Input[T] {}
  val out2 = new Input[T] {}
}
