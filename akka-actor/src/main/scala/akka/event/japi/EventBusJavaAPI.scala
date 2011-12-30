package akka.event.japi

import akka.event._

/**
 * See documentation for [[akka.event.LookupClassification]]
 * E is the Event type
 * S is the Subscriber type
 * C is the Classifier type
 */
abstract class LookupEventBus[E, S, C] extends EventBus with LookupClassification {
  type Event = E
  type Subscriber = S
  type Classifier = C
}

/**
 * See documentation for [[akka.event.SubchannelClassification]]
 * E is the Event type
 * S is the Subscriber type
 * C is the Classifier type
 */
abstract class SubchannelEventBus[E, S, C] extends EventBus with SubchannelClassification {
  type Event = E
  type Subscriber = S
  type Classifier = C
}

/**
 * See documentation for [[akka.event.ScanningClassification]]
 * E is the Event type
 * S is the Subscriber type
 * C is the Classifier type
 */
abstract class ScanningEventBus[E, S, C] extends EventBus with ScanningClassification {
  type Event = E
  type Subscriber = S
  type Classifier = C
}

/**
 * See documentation for [[akka.event.ActorClassification]]
 * An EventBus where the Subscribers are ActorRefs and the Classifier is ActorRef
 * Means that ActorRefs "listen" to other ActorRefs
 * E is the Event type
 */
abstract class ActorEventBus[E] extends akka.event.ActorEventBus with ActorClassification with ActorClassifier {

}
