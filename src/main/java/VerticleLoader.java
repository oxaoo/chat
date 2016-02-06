import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.impl.StringEscapeUtils;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

public class VerticleLoader {
    private static Vertx vertx;

    public static Vertx getVertx() {
        return vertx;
    }

    public static void load() {
        load(null);
    }

    public static void load(Handler<AsyncResult<String>> completionHandler) {
        VertxOptions options = new VertxOptions().setClustered(false);
        //путь до verticle-класса.
        String dir = "chat/src/main/java/";

        try {
            File current = new File(".").getCanonicalFile();
            if (dir.startsWith(current.getName()) && !dir.equals(current.getName())) {
                dir = dir.substring(current.getName().length() + 1);
            }
        } catch (IOException e) {
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
