package rx.async.tck;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class WithExecutor {
    public static ExecutorService executor;

    @BeforeClass
    public static void startExecutor() {
        executor = Executors.newCachedThreadPool(); // do we need to make this configurable?
    }

    @AfterClass
    public static void stopExecutor() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(500, TimeUnit.MILLISECONDS))
                executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
