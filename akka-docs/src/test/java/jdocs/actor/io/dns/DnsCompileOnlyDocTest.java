/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor.io.dns;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.io.Dns;
import akka.io.dns.DnsProtocol;

import static akka.pattern.Patterns.ask;

import scala.Option;

import java.time.Duration;
import java.util.concurrent.CompletionStage;


public class DnsCompileOnlyDocTest {
    public static void example() {
        ActorSystem system = ActorSystem.create();

        ActorRef actorRef = null;
        final Duration timeout = Duration.ofMillis(1000L);

        //#resolve
        Option<Dns.Resolved> initial = Dns.get(system).cache().resolve("google.com", system, actorRef);
        Option<Dns.Resolved> cached = Dns.get(system).cache().cached("google.com");
        //#resolve

        {
            //#actor-api-inet-address
            final ActorRef dnsManager = Dns.get(system).manager();
            CompletionStage<Object> resolved = ask(dnsManager, new Dns.Resolve("google.com"), timeout);
            //#actor-api-inet-address

        }

        {
            //#actor-api-async
            final ActorRef dnsManager = Dns.get(system).manager();
            CompletionStage<Object> resolved = ask(dnsManager, DnsProtocol.resolve("google.com"), timeout);
            //#actor-api-async
        }

        {
            //#srv
            final ActorRef dnsManager = Dns.get(system).manager();
            CompletionStage<Object> resolved = ask(dnsManager, DnsProtocol.resolve("google.com", DnsProtocol.srvRequestType()), timeout);
            //#srv
        }


    }
}
