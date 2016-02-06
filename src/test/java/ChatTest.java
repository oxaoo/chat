import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(VertxUnitRunner.class)
public class ChatTest {
    private Vertx vertx;
    private int port = 8080;
    private Logger log = LoggerFactory.getLogger(ChatTest.class);

    //@Ignore
    @Before
    public void setUp(TestContext context) throws IOException {
        //развертывание нашей Verticle.
        VerticleLoader.load(context.asyncAssertSuccess());
        vertx = VerticleLoader.getVertx();
    }

    //@Ignore
    @After
    public void tearDown(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }

    //@Ignore
    @Test
    public void loadVerticleTest(TestContext context) {
        log.info("*** loadVerticleTest ***");

        Async async = context.async();
        vertx.createHttpClient().getNow(port, "localhost", "/", response ->
        {
            //проверка доступности развернутого нами приложения.
            context.assertEquals(response.statusCode(), 200);
            context.assertEquals(response.headers().get("content-type"), "text/html");

            //проверка содержимого страницы.
            response.bodyHandler(body ->
            {
                context.assertTrue(body.toString().contains("<title>Chat</title>"));
                async.complete();
            });
        });
    }

    //@Ignore
    @Test
    public void eventBusTest(TestContext context) {
        log.info("*** eventBusTest ***");

        Async async = context.async();
        EventBus eb = vertx.eventBus();
        //ожидание события на шине.
        eb.consumer("chat.to.server").handler(message ->
        {
            String getMsg = message.body().toString();
            context.assertEquals(getMsg, "hello");
            async.complete();
        });

        //отправка сообщения на шину.
        eb.publish("chat.to.server", "hello");
    }
}
