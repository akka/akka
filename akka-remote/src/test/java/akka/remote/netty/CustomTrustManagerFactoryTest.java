package akka.remote.netty;


import static org.junit.Assert.assertArrayEquals;

import javax.net.ssl.TrustManager;

import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import akka.remote.transport.netty.CustomTrustManagerFactory;

public class CustomTrustManagerFactoryTest extends JUnitSuite {

    static class TestCustomTrustManagerFactory implements CustomTrustManagerFactory
    {
        @Override
        public TrustManager[] create(String filename, String password) {
            return new TrustManager[0];
        }
    }

    @Test
    public void apiMustBeUsableFromJava() {
        assertArrayEquals(new TrustManager[0], new TestCustomTrustManagerFactory().create(null, null));
    }
}
