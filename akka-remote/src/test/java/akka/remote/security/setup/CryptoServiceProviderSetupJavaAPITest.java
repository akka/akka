package akka.remote.security.setup;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.Some;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class CryptoServiceProviderSetupJavaAPITest extends JUnitSuite
{
    static KeyManager dummyKeyManager = new KeyManager() {};
    static TrustManager dummyTrustManager = new TrustManager() {};
    static KeyManager[] dummyKeyManagers = {dummyKeyManager};
    static TrustManager[] dummyTrustManagers = {dummyTrustManager};
    static ManagerFactoryParameters dummyManagerFactoryParameters = new ManagerFactoryParameters() {};

    @Test
    public void shouldCreateProviderForGivenKeyManagerFactorySpi() throws Throwable {
        KeyManagerFactorySetup setup = KeyManagerFactorySetup.createProviding( DummyKeyManagerFactorySpi.class );

        KeyManager[] keyManagers = KeyManagerFactory.getInstance( KeyManagerFactory.getDefaultAlgorithm(), setup.provider() ).getKeyManagers();
        assertArrayEquals(dummyKeyManagers, keyManagers);
    }

    @Test
    public void shouldCreateProviderForGivenKeyManagerFactorySpiWithParams() throws Throwable {
        KeyManagerFactorySetup setup = KeyManagerFactorySetup.createProviding( DummyKeyManagerFactorySpi.class, dummyManagerFactoryParameters );

        assertEquals(Some.apply(dummyManagerFactoryParameters), setup.keyManagerFactoryParameters());
    }

    @Test
    public void shouldCreateProviderForGivenTrustManagerFactorySpi() throws Throwable {
        TrustManagerFactorySetup setup = TrustManagerFactorySetup.createProviding( DummyTrustManagerFactorySpi.class );

        TrustManager[] trustManagers = TrustManagerFactory.getInstance( TrustManagerFactory.getDefaultAlgorithm(), setup.provider() ).getTrustManagers();
        assertArrayEquals(dummyTrustManagers, trustManagers);
    }

    @Test
    public void shouldCreateProviderForGivenTrustManagerFactorySpiWithParams() throws Throwable {
        TrustManagerFactorySetup setup = TrustManagerFactorySetup.createProviding( DummyTrustManagerFactorySpi.class, dummyManagerFactoryParameters );

        assertEquals(Some.apply(dummyManagerFactoryParameters), setup.trustManagerFactoryParameters());
    }
}

