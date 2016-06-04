import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/**
 * Loader of the Verticles.
 * Convenient to use for testing and debugging app.
 */
public class VerticleLoader {
    private static Vertx vertx;

    /**
     * Simple getter.
     *
     * @return the vertx.
     */
    public static Vertx getVertx() {
        return vertx;
    }

    /**
     * Custom loader of the Verticles.
     */
    public static void load() {
        load(null);
    }

    /**
     * Loader of the Verticles.
     *
     * @param completionHandler a handler which will be notified when the deployment is complete.
     */
    public static void load(Handler<AsyncResult<String>> completionHandler) {
        VertxOptions options = new VertxOptions().setClustered(false);
        //path to the verticle-class.
        String dir = "chat/src/main/java/";

        try {
            File current = new File(".").getCanonicalFile();
            if (dir.startsWith(current.getName()) && !dir.equals(current.getName())) {
                dir = dir.substring(current.getName().length() + 1);
            }
        } catch (IOException ignored) {
        }

        System.setProperty("vertx.cwd", dir);
        String verticleID = Server.class.getName();

        Consumer<Vertx> runner = vertx ->
        {
            try {
                if (completionHandler == null)
                    vertx.deployVerticle(verticleID);
                else
                    vertx.deployVerticle(verticleID, completionHandler);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        };

        if (options.isClustered()) {
            Vertx.clusteredVertx(options, res ->
            {
                if (res.succeeded()) {
                    vertx = res.result();
                    runner.accept(vertx);
                } else {
                    res.cause().printStackTrace();
                }
            });
        } else {
            vertx = Vertx.vertx(options);
            runner.accept(vertx);
        }
    }

    public static void main(String[] args) {
        load();
    }
}
