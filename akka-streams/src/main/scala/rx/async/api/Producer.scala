package rx.async
package api

trait Producer[T] {
  def getPublisher: spi.Publisher[T] // mainly used by the combinators to chain Processors
}

trait Consumer[T] {
  def getSubscriber: spi.Subscriber[T]
}
