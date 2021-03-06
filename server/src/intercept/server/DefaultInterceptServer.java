package intercept.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import intercept.configuration.DefaultProxyConfig;
import intercept.configuration.InterceptConfiguration;
import intercept.configuration.ProxyConfig;
import intercept.framework.Command;
import intercept.framework.WebServer;
import intercept.logging.ApplicationLog;
import intercept.proxy.ProxyFactory;
import intercept.proxy.ProxyServer;
import intercept.server.components.ClasspathContentPresenter;
import intercept.server.components.HomePagePresenter;
import intercept.server.components.NewProxyCommand;
import intercept.server.components.NewProxyPresenter;
import intercept.utils.Block;
import intercept.utils.Utils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static intercept.server.UriMatchers.simpleMatcher;


public class DefaultInterceptServer implements HttpHandler, WebServer, InterceptServer {
    private HttpServer server;
    private InterceptConfiguration configuration;
    private final ApplicationLog applicationLog;
    private static final int WAIT_TIME = 200;

    public DefaultInterceptServer(ApplicationLog applicationLog) {
        this.applicationLog = applicationLog;
    }

    Block<ProxyConfig> startProxies = new Block<ProxyConfig>() {
        public void yield(ProxyConfig item) {
            applicationLog.log("Starting proxy server \"" + item.getName() + "\" on port " + item.getPort());

            ProxyServer proxy = ProxyFactory.startProxy(item, applicationLog);

            applicationLog.trace("Created web context for /" + proxy.getName());
            server.createContext("/" + proxy.getName(), new ProxyConfigurationHttpHandler(proxy, applicationLog));
        }
    };

    @Override
    public void start(InterceptConfiguration configuration) {
        try {
            this.configuration = configuration;
            applicationLog.log("Starting Intercept server on port " + configuration.getConfigurationPort());
            server = HttpServer.create(new InetSocketAddress(configuration.getConfigurationPort()), 0);
            server.createContext("/", this);
            server.setExecutor(null);
            server.start();

            startProxyServers(configuration);

            waitUntilServerAcceptingConnections();

        } catch (IOException e) {
            throw new RuntimeException("Failed to start intercept server ", e);
        }
    }

    private void waitUntilServerAcceptingConnections() {
        while (true) {
            Socket socket = null;
            try {
                socket = new Socket("localhost", configuration.getConfigurationPort());
                if (socket.isConnected()) {
                    Utils.sleep(WAIT_TIME);
                    return;
                }
            } catch (IOException e) {
            } finally {
                Utils.close(socket);
            }
        }
    }


    private void startProxyServers(InterceptConfiguration configuration) {
        configuration.eachProxy(startProxies);
    }

    private void stopProxyServers() {
        ProxyFactory.shutdown();
    }

    public void handle(HttpExchange httpExchange) {
        try {
            String method = httpExchange.getRequestMethod();

            Dispatcher dispatcher = createDispatcher();

            if (method.equalsIgnoreCase("GET")) {
                dispatcher.dispatchGetRequest(new WebContext(this, httpExchange));
            }

            if (method.equalsIgnoreCase("POST")) {
                dispatcher.dispatchPostRequest(new WebContext(this, httpExchange));
            }
        } catch (NoRouteException nre) {
            send404(httpExchange);
        }
        catch (Exception e) {
            send404(httpExchange);
            System.err.println("Error processing request");
            e.printStackTrace();
        }
    }

    private Dispatcher createDispatcher() {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.register(simpleMatcher("/"), new HomePagePresenter(configuration));
        dispatcher.register(simpleMatcher("/proxy/new"), new NewProxyPresenter(), new NewProxyCommand());
        dispatcher.register(UriMatchers.classpathMatcher(), new ClasspathContentPresenter());
        dispatcher.register(simpleMatcher("/stop"), new Command() {
            public void executeCommand(WebContext context) {
                stopProxyServers();
                server.stop(0);
            }
        });
        return dispatcher;
    }

    private void send404(HttpExchange httpExchange) {
        try {
            httpExchange.sendResponseHeaders(404, -1);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public ProxyConfig getConfig() {
        return null;
    }

    @Override
    public List<ProxyConfig> getRunningProxies() {
        final List<ProxyConfig> proxies = new ArrayList<ProxyConfig>();
        configuration.eachProxy(new Block<ProxyConfig>() {
            public void yield(ProxyConfig item) {
                proxies.add(item);
            }
        });
        return proxies;
    }

    @Override
    public void startNewProxy(String name, int port) {
        ProxyConfig proxyConfig = new DefaultProxyConfig(name, port);
        configuration.add(proxyConfig);
        ProxyServer proxy = ProxyFactory.startProxy(proxyConfig, applicationLog);

        startEntrypointForProxy(proxyConfig, proxy);
    }

    private void startEntrypointForProxy(ProxyConfig proxyConfig, ProxyServer proxy) {
        applicationLog.trace("Created web context for /" + proxyConfig.getName());
        server.createContext("/" + proxyConfig.getName(), new ProxyConfigurationHttpHandler(proxy, applicationLog));
    }

    @Override
    public void stop(InterceptConfiguration configuration) {

        try {
            Socket socket = new Socket("localhost", configuration.getConfigurationPort());
            OutputStream outputStream = socket.getOutputStream();
            String message = "POST /stop HTTP1.1\r\n\r\n";
            outputStream.write(message.getBytes());
            outputStream.close();
        } catch (IOException e) {
            System.err.println("Failed to stop intercept server: " + e.getMessage());
        }

        server.stop(0);
        applicationLog.log("Intercept server stopped");
    }

    @Override
    public String uri(String path) {
        return "http://localhost:" + configuration.getConfigurationPort() + path;
    }
}
