package akka.actor;

import akka.actor.ActorSystem;
import akka.japi.Creator;
import akka.testkit.AkkaSpec;
import com.typesafe.config.ConfigFactory;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class StashJavaAPI {

    private static ActorSystem system;

    @BeforeClass
	public static void beforeAll() {
	system = ActorSystem.create("StashJavaAPI", ConfigFactory.parseString(ActorWithStashSpec.testConf()));
    }

    @AfterClass
	public static void afterAll() {
	system.shutdown();
	system = null;
    }

    @Test
	public void mustBeAbleToUseStash() {
	ActorRef ref = system.actorOf(new Props(StashJavaAPITestActor.class).withDispatcher("my-dispatcher"));
	ref.tell("Hello", ref);
	ref.tell("Hello", ref);
	ref.tell(new Object());
    }

}
