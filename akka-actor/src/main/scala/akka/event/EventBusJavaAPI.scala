package akka.event.japi

import akka.event._

abstract class LookupEventBus[E, S, C] extends EventBus with LookupClassification {
  type Event = E
  type Subscriber = S
  type Classifier = C
}

abstract class ScanningEventBus[E, S, C] extends EventBus with ScanningClassification {
  type Event = E
  type Subscriber = S
  type Classifier = C
}

abstract class ActorEventBus[E] extends akka.event.ActorEventBus with ActorClassification with ActorClassifier {

}