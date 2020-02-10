package docs.stream.operators.sourceorflow

object FlatMapConcat {

  implicit val system: ActorSystem = ActorSystem()

  // #flatmap-concat
  val source: Source[String, NotUsed] = Source(List("customer-1", "customer-2"))

  // e.g. could b a query to a database
  def lookupCustomerEvents(customerId: String): Source[String, NotUsed] = {
    Source(List(s"$customerId-event-1", s"$customerId-event-2"))
  }

  source.flatMapConcat(10, customerId => lookupCustomerEvents(customerId)).runForeach(println)

  // prints - events from each customer consecutively
  // customer-1-event-1
  // customer-1-event-2
  // customer-2-event-1
  // customer-2-event-2
  // #flatmap-concat

}

