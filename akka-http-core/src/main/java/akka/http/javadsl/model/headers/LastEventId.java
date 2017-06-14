/*
 * Copyright 2015 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.sse.ServerSentEvent;

/**
 * The Last-Event-ID header is sent by a client to the server to signal the ID of the last sever-sent event received.
 */
public abstract class LastEventId extends akka.http.scaladsl.model.HttpHeader {

    /**
     * Creates a Last-Event-ID header.
     *
     * @param id ID of the last event, encoded as UTF-8 string
     *
     * @see ServerSentEvent
     */
    public static LastEventId create(String id) {
        return akka.http.scaladsl.model.headers.Last$minusEvent$minusID$.MODULE$.apply(id);
    }

    public abstract String id();
}
