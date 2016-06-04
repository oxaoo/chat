import com.google.gson.Gson;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Starter;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeEvent;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;

import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static io.vertx.ext.web.handler.sockjs.BridgeEvent.Type.*;

/**
 * The app server containing logic of the chat.
 */
public class Server extends AbstractVerticle {
    private Logger log = LoggerFactory.getLogger(Server.class);
    private SockJSHandler handler = null;
    private AtomicInteger online = new AtomicInteger(0);

    /**
     * Entry point in the app.
     */
    @Override
    public void start() {

        if (!deploy()) {
            log.error("Failed to deploy the server.");
            return;
        }

        handle();
    }

    /**
     * Deployment of the app.
     *
     * @return deployment result.
     */
    private boolean deploy() {
        int hostPort = getFreePort();

        if (hostPort < 0)
            return false;

        Router router = Router.router(vertx);

        //the event handler.
        handler = SockJSHandler.create(vertx);

        router.route("/eventbus/*").handler(handler);
        router.route().handler(StaticHandler.create());

        //start of the web-server.
        vertx.createHttpServer().requestHandler(router::accept).listen(hostPort);

        try {
            String addr = InetAddress.getLocalHost().getHostAddress();
            log.info("Access to \"CHAT\" at the following address: \nhttp://" + addr + ":" + hostPort);
        } catch (UnknownHostException e) {
            log.error("Failed to get the local address: [" + e.toString() + "]");
            return false;
        }

        return true;
    }

    /**
     * Receive a free port to deploy the app.
     *
     * @return the free port.
     */
    private int getFreePort() {
        int hostPort = 8080;

        //if the port is set as argument,
        // when the app starts.
        if (Starter.PROCESS_ARGS != null
                && Starter.PROCESS_ARGS.size() > 0) {
            try {
                hostPort = Integer.valueOf(Starter.PROCESS_ARGS.get(0));
            } catch (NumberFormatException e) {
                log.warn("Invalid port: [" + Starter.PROCESS_ARGS.get(0) + "]");
            }
        }

        //if the port is incorrectly specified.
        if (hostPort < 0 || hostPort > 65535)
            hostPort = 8080;

        return getFreePort(hostPort);
    }

    /**
     * Receive a free port to deploy the app.
     * if a port value is specified as 0,
     * that is given a random free port.
     *
     * @param hostPort the desired port.
     * @return the available port.
     */
    private int getFreePort(int hostPort) {
        try {
            ServerSocket socket = new ServerSocket(hostPort);
            int port = socket.getLocalPort();
            socket.close();

            return port;
        } catch (BindException e) {
            //is executed when the specified port is already in use.
            if (hostPort != 0)
                return getFreePort(0);

            log.error("Failed to get the free port: [" + e.toString() + "]");
            return -1;
        } catch (IOException e) {
            log.error("Failed to get the free port: [" + e.toString() + "]");
            return -1;
        }
    }

    /**
     * Registration of an event handler.
     */
    private void handle() {
        BridgeOptions opts = new BridgeOptions()
                .addInboundPermitted(new PermittedOptions().setAddress("chat.to.server"))
                .addOutboundPermitted(new PermittedOptions().setAddress("chat.to.client"));

        //processing incoming events.
        handler.bridge(opts, event -> {
            if (event.type() == PUBLISH)
                publishEvent(event);

            if (event.type() == REGISTER)
                registerEvent(event);

            if (event.type() == SOCKET_CLOSED)
                closeEvent();

            //note that after the event processing
            // must be called speaks for itself method.
            event.complete(true);
        });
    }

    /**
     * Publication of the message.
     *
     * @param event contains a message.
     * @return result of the publication.
     */
    private boolean publishEvent(BridgeEvent event) {
        if (event.rawMessage() != null
                && event.rawMessage().getString("address").equals("chat.to.server")) {
            String message = event.rawMessage().getString("body");
            if (!verifyMessage(message))
                return false;

            String host = event.socket().remoteAddress().host();
            int port = event.socket().remoteAddress().port();

            Map<String, Object> publicNotice = createPublicNotice(host, port, message);
            vertx.eventBus().publish("chat.to.client", new Gson().toJson(publicNotice));
            return true;
        } else
            return false;
    }

    /**
     * Creation of the notice of the publication of the message.
     *
     * @param host is address to which a message is published.
     * @param port is port to which a message is published.
     * @param message published message.
     * @return wrapper of the published message as the notice.
     */
    private Map<String, Object> createPublicNotice(String host, int port, String message) {
        Date time = Calendar.getInstance().getTime();

        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "publish");
        notice.put("time", time.toString());
        notice.put("host", host);
        notice.put("port", port);
        notice.put("message", message);
        return notice;
    }

    /**
     * Registration of the handler.
     *
     * @param event contains of the address.
     */
    private void registerEvent(BridgeEvent event) {
        if (event.rawMessage() != null
                && event.rawMessage().getString("address").equals("chat.to.client"))
            new Thread(() ->
            {
                Map<String, Object> registerNotice = createRegisterNotice();
                vertx.eventBus().publish("chat.to.client", new Gson().toJson(registerNotice));
            }).start();
    }

    /**
     * Creation of the notice of registration of the user.
     *
     * @return registration notice.
     */
    private Map<String, Object> createRegisterNotice() {
        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "register");
        notice.put("online", online.incrementAndGet());
        return notice;
    }

    /**
     * Closing of the socket.
     */
    private void closeEvent() {
        new Thread(() ->
        {
            Map<String, Object> closeNotice = createCloseNotice();
            vertx.eventBus().publish("chat.to.client", new Gson().toJson(closeNotice));
        }).start();
    }

    /**
     * Creation of the notice of the user's exit from a chat.
     *
     * @return wrapper of the information about user's exit as the notice.
     */
    private Map<String, Object> createCloseNotice() {
        Map<String, Object> notice = new TreeMap<>();
        notice.put("type", "close");
        notice.put("online", online.decrementAndGet());
        return notice;
    }

    /**
     * Pretty simple verification of the message,
     * of course it can be complicated,
     * but for example it's enough ;)
     *
     * @param msg incoming message.
     * @return verification result.
     */
    private boolean verifyMessage(String msg) {
        return msg.length() > 0
                && msg.length() <= 140;
    }
}