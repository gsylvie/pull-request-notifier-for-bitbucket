package se.bjurr.prnfb.http;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Throwables.propagate;
import static org.slf4j.LoggerFactory.getLogger;

import com.atlassian.event.api.EventListener;
import com.atlassian.plugin.event.events.PluginDisablingEvent;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.sal.api.lifecycle.LifecycleAware;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Named;
import javax.net.ssl.SSLContext;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.ProxyAuthenticationStrategy;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.ssl.TrustStrategy;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;

@ExportAsService({HttpUtil.class})
@Named("PRNotifier_HttpUtil")
public class HttpUtil implements LifecycleAware {
  private static final Logger LOG = getLogger(HttpUtil.class);
  private static volatile CloseableHttpClient main = null;
  private static final Map<HttpHost, CloseableHttpClient> proxies = new ConcurrentHashMap<>();

  public HttpUtil() {}

  public static final TreeMap<Long, String[]> LAST_25_SUCCESSES = new TreeMap<>();
  public static final TreeMap<Long, String[]> LAST_25_FAILURES = new TreeMap<>();
  public static final TreeMap<Long, String[]> LAST_25_ERRORS = new TreeMap<>();
  public static final TreeMap<Long, String[]> LAST_25_IN_FLIGHT = new TreeMap<>();

  public static void reset() {
    if (main != null) {
      try {
        main.close();
      } catch (Throwable t) {
        // swallow
      }
      main = null;
    }
    for (CloseableHttpClient c : proxies.values()) {
      if (c != null) {
        try {
          c.close();
        } catch (Throwable t) {
          // swallow
        }
      }
    }
    proxies.clear();
  }

  private static CloseableHttpClient getCachedClient(final UrlInvoker u, final HttpHost h) {
    CloseableHttpClient client;
    if (h != null) {
      // proxy=true
      client = proxies.get(h);
      if (client == null) {
        HttpClientBuilder builder = HttpClientBuilder.create();
        configureSsl(u, builder, true);
        configureForProxy(u, h, builder);
        client = builder.build();
        proxies.put(h, client);
      }
    } else {
      // proxy=false
      client = main;
      if (client == null) {
        HttpClientBuilder builder = HttpClientBuilder.create();
        configureSsl(u, builder, false);
        client = builder.build();
        main = client;
      }
    }
    return client;
  }

  public static HttpResponse doInvoke(final UrlInvoker u, final HttpRequestBase httpRequestBase) {
    SimpleDateFormat df = new SimpleDateFormat("YYYY-MM-dd'T'HH:mm:ss.SSSZ");
    HttpHost h = u.getHttpHostForProxy();
    CloseableHttpClient client = getCachedClient(u, h);
    CloseableHttpResponse httpResponse = null;
    long contentLength = -1;
    if (httpRequestBase instanceof HttpEntityEnclosingRequestBase) {
      HttpEntityEnclosingRequestBase b = (HttpEntityEnclosingRequestBase) httpRequestBase;
      contentLength = b.getEntity().getContentLength();
    }
    long start = System.currentTimeMillis();
    Date d = new Date(start);
    final URI uri = httpRequestBase.getURI();
    String[] forLog =
        new String[] {
          df.format(d),
          "-",
          "-",
          httpRequestBase.getMethod(),
          "" + contentLength,
          uri.toString(),
          "-",
          "-",
          h != null ? "PROXY: " + h : "-"
        };
    put(LAST_25_IN_FLIGHT, start, forLog);
    long delay = -1;
    try {
      httpResponse = client.execute(httpRequestBase);
      delay = System.currentTimeMillis() - start;
      forLog[1] = delay + "ms";
      final int statusCode = httpResponse.getStatusLine().getStatusCode();
      forLog[2] = Integer.toString(statusCode);

      final HttpEntity entity = httpResponse.getEntity();
      String entityString = "";
      if (entity != null) {
        entityString = EntityUtils.toString(entity, UTF_8);
      }
      forLog[6] = "" + entityString.length();

      if (200 <= statusCode && statusCode <= 299) {
        put(LAST_25_SUCCESSES, start, forLog);
      } else {
        put(LAST_25_FAILURES, start, forLog);
      }
      return new HttpResponse(uri, statusCode, entityString);

    } catch (final Exception e) {
      if (delay == -1) {
        delay = System.currentTimeMillis() - start;
      }
      forLog[1] = delay + "ms";
      forLog[2] = "ERR";
      forLog[8] = e.toString();

      put(LAST_25_ERRORS, start, forLog);
      LOG.error("PR-Notifier-HTTP-Failure - " + e, e);

    } finally {
      try {
        if (httpResponse != null) {
          httpResponse.close();
        }
      } catch (final IOException e) {
        propagate(e);
      }
    }
    return null;
  }

  private static void put(final TreeMap<Long, String[]> m, Long l, String[] v) {
    synchronized (m) {
      while (m.size() > 24) {
        m.pollFirstEntry();
      }
      m.put(l, v);
    }
    if (m != LAST_25_IN_FLIGHT) {
      synchronized (LAST_25_IN_FLIGHT) {
        LAST_25_IN_FLIGHT.remove(l);
      }
    }
  }

  private static SSLContext newSslContext(UrlInvoker u) throws Exception {
    SSLContextBuilder sslContextBuilder = SSLContexts.custom();
    if (u.shouldAcceptAnyCertificate()) {
      sslContextBuilder = doAcceptAnyCertificate(sslContextBuilder);
    }
    ClientKeyStore cks = u.getClientKeyStore();
    if (cks != null && cks.getKeyStore().isPresent()) {
      sslContextBuilder.loadKeyMaterial(cks.getKeyStore().get(), cks.getPassword());
    }
    return sslContextBuilder.build();
  }

  private static SSLContextBuilder doAcceptAnyCertificate(SSLContextBuilder customContext)
      throws Exception {
    final TrustStrategy easyStrategy =
        new TrustStrategy() {
          @Override
          public boolean isTrusted(final X509Certificate[] chain, final String authType) {
            return true;
          }
        };
    customContext = customContext.loadTrustMaterial(null, easyStrategy);
    return customContext;
  }

  private static void configureSsl(
      UrlInvoker u, final HttpClientBuilder builder, final boolean isProxy) {
    PoolingHttpClientConnectionManager cm = null;
    try {
      SSLContext s = newSslContext(u);
      SSLConnectionSocketFactory sslConnSocketFactory = new SSLConnectionSocketFactory(s);
      builder.setSSLSocketFactory(sslConnSocketFactory);
      builder.setSSLContext(s);
      Registry<ConnectionSocketFactory> registry =
          RegistryBuilder.<ConnectionSocketFactory>create()
              .register("https", sslConnSocketFactory)
              .register("http", PlainConnectionSocketFactory.getSocketFactory())
              .build();
      cm = new PoolingHttpClientConnectionManager(registry);
    } catch (final Exception e) {
      propagate(e);
    }

    final RequestConfig config =
        RequestConfig.custom()
            .setMaxRedirects(8)
            .setConnectTimeout(7500)
            .setConnectionRequestTimeout(7500)
            .setSocketTimeout(45000)
            .build();
    builder.setDefaultRequestConfig(config);
    if (isProxy) {
      cm.setMaxTotal(12);
      cm.setDefaultMaxPerRoute(4);
    } else {
      cm.setMaxTotal(42);
      cm.setDefaultMaxPerRoute(8);
    }
    builder.setConnectionManager(cm);
  }

  @VisibleForTesting
  private static void configureForProxy(
      final UrlInvoker u, final HttpHost h, final HttpClientBuilder builder) {
    if (u.getProxyUser().isPresent() && u.getProxyPassword().isPresent()) {
      final String username = u.getProxyUser().get();
      final String password = u.getProxyPassword().get();
      final UsernamePasswordCredentials creds = new UsernamePasswordCredentials(username, password);
      final CredentialsProvider credsProvider = new BasicCredentialsProvider();
      credsProvider.setCredentials(new AuthScope(h.getHostName(), h.getPort()), creds);
      builder.setDefaultCredentialsProvider(credsProvider);
    }
    builder.useSystemProperties();
    builder.setProxy(h);
    builder.setProxyAuthenticationStrategy(new ProxyAuthenticationStrategy());
  }

  @Override
  public void onStart() {}

  @Override
  public void onStop() {
    reset();
  }

  // This is the important one (onPluginDisabling) that actually gets invoked on shutdown!
  @EventListener
  public void onPluginDisabling(final PluginDisablingEvent event) {
    onStop();
  }
}
