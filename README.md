# Vert.x Tutorial: Creating a simple chat

*Original article in Russian is provided on the [Habrahabr](https://habrahabr.ru/post/276771/).*

In this tutorial it's told how to create a simple chat using Vert.x 3.

## In more detail about a chat
The application is started on the server, after deployment the address of an anonymous chat published to which it is possible to join, via any browser. 
To this address the application broadcasts in real time of the message from all users.

![chatroom](/screenshots/chatroom.png)

Develop will be in IntelliJ IDEA 15, [Community-version](https://www.jetbrains.com/idea/download/) is sufficient.


## Structure of the project
We create the maven-project. Unfortunately there is no ready archetype for vert.x 3 (though for 2 exists) therefore we generate the sample maven-project. 
His final structure will be as follows:

```code
src
+---main
|   +---java
|   |   |   Server.java
|   |   |   VerticleLoader.java
|   |   |
|   |   \---webroot
|   |           date-format.js
|   |           index.html
|   |           vertx-eventbus.js
|   |
|   \---resources
\---test
    \---java
            ChatTest.java
```

In pom.xml we set the following dependences. 
Where vertx-core library of support Verticles (in more detail, what is it, is a little farther), vertx-web – allows to use an event handler (and not only) and vertx-unit – for unit testing.

```xml
<dependencies>
    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-core</artifactId>
        <version>3.0.0</version>
    </dependency>

    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-web</artifactId>
        <version>3.0.0</version>
    </dependency>

    <dependency>
        <groupId>io.vertx</groupId>
        <artifactId>vertx-unit</artifactId>
        <version>3.0.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```


## Server
A feature of this framework is that all components shall be presented in the form by Verticle.
Verticle is some analog of a servlet, is atomic unit of deployment. 
Developers describe Verticle as something similar to the actor in [model of actors](https://en.wikipedia.org/wiki/Actor_model). 
Actually, this construction allows to organize a high level of parallelism and asynchrony, than Vert.x is famous. 
In implementation of our server, we inherit the abstract class of AbstractVerticle.

The start() method overrides by us is entry point in the program. 
At first application deployment – the deploy() function is executed, then the handle() method is specified.

```java
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
//...
}
```

For application deployment it is necessary to receive the free port if it wasn't succeeded to receive it, in hostPort there will be the negative value. 
Further we create a router, specify for it a destination address and specify the handler. 
And at last, launch HTTP-Server on available port.

```java
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
```
Process of receiving the free port is given in a code fragment below. 
At first the PROCESS_ARGS static-field is checked for existence of arguments of an application launch, one of which can be a port of application deployment set by the user. 
If the port wasn't set, the port by default is used: 8080.

```java
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
```

If the argument of the socket creation constructor is specified with a value of 0, then the random free port will be given.  
When the port is already occupied (for example, the port 8080 is already used by other application, but at the same time, it is specified as argument of start of the current application), BindException exception is thrown out, repeated attempt of receiving the free port is in that case executed.

```java
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
```

In case of successful deployment, starts listening of the bus of events to addresses: chat.to.server (the entering events) and chat.to.client (the outgoing events).  
After processing the next event on the bus, it is necessary to check this event.

```java
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
```

Any events which take place on the bus can be provided by 7 following types:  
SOCKET_CREATED -- occurs when creating the socket  
SOCKET_CLOSED -- when closing a socket  
SEND -- attempt of sending the message from the client to the server  
PUBLISH -- the publication of the message the client for the server  
RECEIVE -- the notification message from the server, about the delivered message  
REGISTER -- attempt to register the handler  
UNREGISTER -- attempt to cancel the registered handler  


In our application it is only enough to us to process events with the PUBLISH, REGISTER and SOCKET_CLOSED types.  
The event with the PUBLISH type works when someone from users sends the message to a chat. 
REGISTER – works when the user registers the handler. 
Why not SOCKET_CREATED? 
Because, the event with the SOCKET_CREATED type precedes – REGISTER, and, naturally, until the client registers the handler, he won't be able to receive events.
SOCKET_CLOSED – arises, always when the user leaves a chat or when there is an unforeseen situation.  
In case of the publication of the message, the handler works and calls the publishEvent method. 
The assignment address is checked if it is correct, the message is derived, then checked and published on the bus of events for all clients (including and the sender).

```java
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
```

Generation of the notification message for the publication of the message looks as follows:

```java
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
```

Entry and exit of users in chat are handled in the following method:

```java
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
```

Verification of the published message rather primitive, but for an example and it is enough, i.e. you can complicate it checking, for example, for transmission of scripts in the form of the message and other hacks.

```java
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
```

To exchange data using JSON format, so you need to update the pom.xml file to add the following dependence:

```xml
<dependency>
    <groupId>com.google.code.gson</groupId>
    <artifactId>gson</artifactId>
    <version>2.3.1</version>
</dependency>
```

Also, in our chat will be displayed count of the number of online users, as our application is multi-threaded, it is guaranteed to be thread-safety, so the easiest way to declare our count how AtomicInteger.


## Client

We create index.html in the section webroot as it is provided on structure at the beginning of article. 
For communication with the server, to be exact, with the bus of events the vertx-eventbus.js library is used.  
For formatting of date, we will use date-format.js library, pretty simple and convenient. 
In addition, as html of design we will use bootstrap of version 3.3.5, sockjs.js of version 0.3.4 necessary for vertx-eventbus.js and jquery library of version 1.11.3.  
The handler of the bus of events on client side looks as follows:

```javascript
        var online = 0; //counter of online users.
        var eb = new EventBus("/eventbus/"); //event bus.

        eb.onopen = function() {
            //registration event handler in the chat.
            eb.registerHandler("chat.to.client", eventChatProcessing);
        };

        //event handler in the chat.
        function eventChatProcessing(err, msg) {
            var event = jQuery.parseJSON(msg.body);

			if (event.type == 'publish') {//is message.
				var time = Date.parse(event.time);
				var formattedTime = dateFormat(time, "dd.mm.yy HH:MM:ss");

				//add the message.
				appendMsg(event.host, event.port, event.message, formattedTime);
			} else { //change of number of users.
			    //type: register or close.
			    online = event.online;
				$('#online').text(online);
			}
        };
```

If publish event type (i.e. the publication of the message), this are created of an event in a tuple and join the table of messages. 
Otherwise, when the type of an event corresponds to the new or left user, the counter online of users is just updated. 
Function of adding of the message is pretty simple.

```javascript
        //adding of new message.
		function appendMsg(host, port, message, formattedTime){
			var $msg = $('<tr bgcolor="#dff0d8"><td align="left">' + formattedTime
					+ '</td><td align="left">' + host + ' [' + port + ']'
					+ '</td><td>' + message
					+ '</td></tr>');

            var countMsg = $('#messages tr').length;
			if (countMsg == 0)
				$('#messages').append($msg);
			else
			    $('#messages > tbody > tr:first').before($msg);
		}
```

During sending the message, it at first is published to the address "chat.to.server" where it is processed by the server if the message passes verification, it is delivered all clients, including and the sender.

```javascript
        $(document).ready(function() {
            //event of sending of the message.
            $('#chatForm').submit(function(evt) {
                evt.preventDefault();
                var message = $('#message').val();
                if (message.length > 0) {
                    //sending of the message on the event bus.
                    eb.publish("chat.to.server", message);
                    $('#message').val("").focus();
                    countChar();
                }
            });
        });
```

Finally the last method that handles the number of characters on the condition, the user can not enter a message longer than 140 characters.

```javascript
        //counter of the entered characters.
        function countChar() {
            var len = $('#message').val().length;
            if (len > 140) {
                var msg = $('#message').val().substring(0, 140);
                $('#message').val(msg);
            } else {
                $('#charNum').text(140 - len);
                var per = 100 / 140 * len;
                $('#charNumProgressBar').css('width', per+'%').attr('aria-valuenow', per);
            }
        };
```

For start and comfortable debugging, I recommend to write own Verticle loader.  
Only, value which initializes the dir variable shall be actual, i.e. actually there shall be such path. 
And also, the verticleID variable shall be initialized by a name of the launched verticle-class, all remaining code isn't subject to change.

```java
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
```

Now, when the loader is ready, create a launch configuration:  
**Run – Edit Configuration … – Add New Configuration (Alt + Insert) – Application.**  
Specify Main Class as VerticleLoader, save a configuration and we launch.
![loader](/screenshots/loader.png)


## Testing
Let's test the application developed by us. 
We will do it with use of JUnit therefore it is necessary to open for pom.xml again and to add the following dependence:

```xml
<dependency>
    <groupId>junit</groupId>
    <artifactId>junit</artifactId>
    <version>4.12</version>
    <scope>test</scope>
</dependency>
```

The setUp() Vertx we create an instance and deploys it on our Verticle. 
Unlike traditional methods of JUnit, all current methods of getting more TestContext. 
The purpose of this object comply with asynchronous our tests.  
The method tearDown() is called for an object TestContext asyncAssertSuccess(), it fails, if the shutdown Verticle any problems.

```java
@RunWith(VertxUnitRunner.class)
public class ChatTest {
    private Vertx vertx;
    private int port = 8080;
    private Logger log = LoggerFactory.getLogger(ChatTest.class);

    //@Ignore
    @Before
    public void setUp(TestContext context) throws IOException {
        //deployment of our Verticle.
        VerticleLoader.load(context.asyncAssertSuccess());
        vertx = VerticleLoader.getVertx();
    }

    //@Ignore
    @After
    public void tearDown(TestContext context) {
        vertx.close(context.asyncAssertSuccess());
    }
//...
}
```

In the method we check loadVerticleTest loading our application. 
We create a client and try to make sure that the application is deployed to the specified by us address is available. 
If successful, we get a status code 200.  
Then, try to get the content of the page header which must contain the "Chat" text.
Since the request and response operations are asynchronous, so you need it as a monitor and be notified when the test was completed for this purpose Async object that causes always complete() method on the test completion.

```java
    @Test
    public void loadVerticleTest(TestContext context) {
        log.info("*** loadVerticleTest ***");

        Async async = context.async();
        vertx.createHttpClient().getNow(port, "localhost", "/", response ->
        {
            //check of availability of the app deployed by us.
            context.assertEquals(response.statusCode(), 200);
            context.assertEquals(response.headers().get("content-type"), "text/html");

            //check of contents of the page.
            response.bodyHandler(body ->
            {
                context.assertTrue(body.toString().contains("<title>Chat</title>"));
                async.complete();
            });
        });
    }
```

In the eventBusTest method the client of the bus of events is created and the processor is specified. 
At that time, while the client waits for any events on the bus, the message is published. 
The processor reacts to it and checks a body of the entering event for equivalence, in case of successful check the test comes to the end with calling async.complete().

```java
    @Test
    public void eventBusTest(TestContext context) {
        log.info("*** eventBusTest ***");

        Async async = context.async();
        EventBus eb = vertx.eventBus();
        //waiting for an event on the bus.
        eb.consumer("chat.to.server").handler(message ->
        {
            String getMsg = message.body().toString();
            context.assertEquals(getMsg, "hello");
            async.complete();
        });

        //sending a message to the bus.
        eb.publish("chat.to.server", "hello");
    }
```

Launch tests.
![runtests](/screenshots/runtests.png)


## Assembly and start of the executed module
For this purpose it is necessary to add maven-shade-plugin to pom.xml. 
Where Main-Verticle in our case shall specify the class Server.

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>2.3</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals>
                <goal>shade</goal>
            </goals>
            <configuration>
                <transformers>
                    <transformer
                            implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <manifestEntries>
                            <Main-Class>io.vertx.core.Starter</Main-Class>
                            <Main-Verticle>Server</Main-Verticle>
                        </manifestEntries>
                    </transformer>
                </transformers>
                <artifactSet/>
                <outputFile>${project.build.directory}/${project.artifactId}-${project.version}-fat.jar</outputFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

Execute the *Run Maven Build* command then in the directory target chat-1.0-fat.jar will appear. 
For an application launch the executed module and the webroot folder shall be in one directory. 
To tear our application on port 12345 it is necessary to execute a command: **java - jar chat-1.0-fat.jar 12345**
  
***That's all. Good luck!***


____
Full source code is represented in this project in the directory [src](/src).

## Useful resources

* [Vert.x Documentation](http://vertx.io/docs/);
* [Vert.x-Web Documentation](http://vertx.io/docs/vertx-web/java/);
* [My first Vert.x 3 Application](http://vertx.io/blog/my-first-vert-x-3-application/);
* [Vert.x examples on GitHub](https://github.com/vert-x3/vertx-examples);
* [Vert.x Tutorial by Jakob Jenkov](http://tutorials.jenkov.com/vert.x/index.html).