package akka.lease.javadsl;

import akka.actor.ActorSystem;
import akka.lease.scaladsl.LeaseProviderSpec;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LeaseProviderTest {
    @Rule
    public AkkaJUnitActorSystemResource actorSystemResource =
            new AkkaJUnitActorSystemResource("LoggingAdapterTest", LeaseProviderSpec.config());

    private ActorSystem system = null;

    @Before
    public void before() {
        system = actorSystemResource.getSystem();
    }

    @Test
    public void loadLeaseImpl() {
        Lease leaseA = LeaseProvider.get(system).getLease("a", "lease-a", "owner1");

        assertEquals(leaseA.getSetting().leaseName(), "a");
        assertEquals(leaseA.getSetting().ownerName(), "owner1");
        assertEquals(leaseA.getSetting().leaseConfig().getString("key1"), "value1");

        Lease leaseB = LeaseProvider.get(system).getLease("b", "lease-b", "owner2");

        assertEquals(leaseB.getSetting().leaseName(), "b");
        assertEquals(leaseB.getSetting().ownerName(), "owner2");
        assertEquals(leaseB.getSetting().leaseConfig().getString("key2"), "value2");
    }
}
