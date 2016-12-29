package docs.http.javadsl.server.testkit;

//#source-quote
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

public class TestKitFragmentTest extends JUnitRouteTest {
    class FragmentTester extends AllDirectives {
        public Route createRoute(Route fragment) {
            return
                    pathPrefix("test", () ->
                            fragment
                    );
        }
    }

    TestRoute fragment = testRoute(new MyAppFragment().createRoute());
    TestRoute testRoute = testRoute(new FragmentTester().createRoute(fragment.underlying()));

    @Test
    public void testFragment() {
        testRoute.run(HttpRequest.GET("/test"))
                .assertStatusCode(200)
                .assertEntity("Fragments of imagination");

        testRoute.run(HttpRequest.PUT("/test"))
                .assertStatusCode(StatusCodes.METHOD_NOT_ALLOWED);
    }
}
//#source-quote
