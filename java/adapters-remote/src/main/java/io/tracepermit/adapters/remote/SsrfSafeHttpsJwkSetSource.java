package io.tracepermit.adapters.remote;

import io.tracepermit.identity.OidcJwkSetSource;
import java.io.IOException;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.FormBody;

/**
 * Fetches only operator-allowlisted JWKS documents over a DNS-pinned HTTPS connection.
 *
 * <p>The public constructor requires globally routable addresses and the platform trust store. The
 * package-private constructor exists only so live TLS tests can use a runtime-generated certificate
 * and loopback server without weakening production defaults.
 */
public final class SsrfSafeHttpsJwkSetSource implements OidcJwkSetSource {
  static final int MAXIMUM_RESPONSE_BYTES = 65_536;
  private static final int MAXIMUM_RESOLVED_ADDRESSES = 8;
  private static final Duration MAXIMUM_TIMEOUT = Duration.ofSeconds(10);

  @FunctionalInterface
  interface HostResolver {
    List<InetAddress> resolve(String hostname) throws UnknownHostException;
  }

  private final Set<URI> allowedUris;
  private final Duration connectTimeout;
  private final Duration callTimeout;
  private final HostResolver resolver;
  private final Predicate<InetAddress> addressPolicy;
  private final SSLSocketFactory sslSocketFactory;
  private final X509TrustManager trustManager;
  private final BoundedHostResolver boundedResolver;

  public SsrfSafeHttpsJwkSetSource(
      Set<URI> allowedUris, Duration connectTimeout, Duration callTimeout) {
    this(
        allowedUris,
        connectTimeout,
        callTimeout,
        hostname -> List.of(InetAddress.getAllByName(hostname)),
        SsrfSafeHttpsJwkSetSource::isPublicAddress,
        null,
        null,
        BoundedHostResolver.defaultResolver());
  }

  SsrfSafeHttpsJwkSetSource(
      Set<URI> allowedUris,
      Duration connectTimeout,
      Duration callTimeout,
      HostResolver resolver,
      Predicate<InetAddress> addressPolicy,
      SSLSocketFactory sslSocketFactory,
      X509TrustManager trustManager) {
    this(
        allowedUris,
        connectTimeout,
        callTimeout,
        resolver,
        addressPolicy,
        sslSocketFactory,
        trustManager,
        BoundedHostResolver.defaultResolver());
  }

  SsrfSafeHttpsJwkSetSource(
      Set<URI> allowedUris,
      Duration connectTimeout,
      Duration callTimeout,
      HostResolver resolver,
      Predicate<InetAddress> addressPolicy,
      SSLSocketFactory sslSocketFactory,
      X509TrustManager trustManager,
      BoundedHostResolver boundedResolver) {
    Objects.requireNonNull(allowedUris);
    if (allowedUris.isEmpty()) {
      throw new IllegalArgumentException("At least one JWKS URI must be allowlisted.");
    }
    Set<URI> checked = new LinkedHashSet<>();
    for (URI uri : allowedUris) checked.add(requireSafeUri(uri));
    this.allowedUris = Set.copyOf(checked);
    this.connectTimeout = boundedTimeout(connectTimeout, "connectTimeout");
    this.callTimeout = boundedTimeout(callTimeout, "callTimeout");
    this.resolver = Objects.requireNonNull(resolver);
    this.addressPolicy = Objects.requireNonNull(addressPolicy);
    if ((sslSocketFactory == null) != (trustManager == null)) {
      throw new IllegalArgumentException(
          "A custom TLS socket factory and trust manager must be supplied together.");
    }
    this.sslSocketFactory = sslSocketFactory;
    this.trustManager = trustManager;
    this.boundedResolver = Objects.requireNonNull(boundedResolver);
  }

  @Override
  public String fetch(URI configuredJwksUri) {
    return request(configuredJwksUri, null, null);
  }

  /** Same exact-URI, DNS-pinned, no-proxy/no-redirect transport for an operator token endpoint. */
  public String postForm(URI tokenUri, java.util.Map<String, String> form, String basicAuthorization) {
    FormBody.Builder body = new FormBody.Builder();
    form.forEach(body::add);
    return request(tokenUri, body.build(), basicAuthorization);
  }

  private String request(URI configuredJwksUri, FormBody form, String authorization) {
    URI uri = requireSafeUri(configuredJwksUri);
    if (!allowedUris.contains(uri)) throw new JwksFetchException();

    long startNanos = System.nanoTime();
    long totalBudgetNanos = callTimeout.toNanos();

    List<InetAddress> approvedAddresses = resolveAndValidate(uri.getHost(), totalBudgetNanos);

    long elapsedNanos = System.nanoTime() - startNanos;
    long remainingNanos = totalBudgetNanos - elapsedNanos;
    if (remainingNanos <= 0) {
      throw new JwksFetchException();
    }

    Duration httpTimeout = Duration.ofNanos(remainingNanos);
    Duration effectiveConnectTimeout =
        connectTimeout.compareTo(httpTimeout) < 0 ? connectTimeout : httpTimeout;

    Dns pinnedDns =
        hostname -> {
          if (!canonicalHost(hostname).equals(canonicalHost(uri.getHost()))) {
            throw new UnknownHostException("The host is outside the pinned JWKS destination.");
          }
          return approvedAddresses;
        };

    OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(effectiveConnectTimeout)
            .readTimeout(httpTimeout)
            .callTimeout(httpTimeout);
    if (sslSocketFactory != null) {
      clientBuilder.sslSocketFactory(sslSocketFactory, trustManager);
    }

    Request.Builder requestBuilder = new Request.Builder()
            .url(uri.toString())
            .header("Accept", "application/jwk-set+json, application/json")
            .header("Cache-Control", "no-store");
    if (form == null) requestBuilder.get();
    else requestBuilder.post(form).header("Authorization", authorization);
    Request request = requestBuilder.build();
    try (Response response = clientBuilder.build().newCall(request).execute()) {
      if (response.code() != 200 || response.handshake() == null || response.body() == null) {
        throw new JwksFetchException();
      }
      String contentType = response.header("Content-Type");
      if (!isJsonContentType(contentType)) throw new JwksFetchException();
      long declaredLength = response.body().contentLength();
      if (declaredLength > MAXIMUM_RESPONSE_BYTES) throw new JwksFetchException();
      byte[] bytes = response.body().byteStream().readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
      if (bytes.length > MAXIMUM_RESPONSE_BYTES) throw new JwksFetchException();
      return decodeUtf8(bytes);
    } catch (JwksFetchException exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new JwksFetchException();
    }
  }

  private List<InetAddress> resolveAndValidate(String hostname, long totalBudgetNanos) {
    if (totalBudgetNanos <= 0) {
      throw new JwksFetchException();
    }
    try {
      List<InetAddress> raw =
          boundedResolver.resolve(resolver, hostname, Duration.ofNanos(totalBudgetNanos));
      if (raw == null || raw.isEmpty() || raw.size() > MAXIMUM_RESOLVED_ADDRESSES) {
        throw new JwksFetchException();
      }
      List<InetAddress> addresses = List.copyOf(raw);
      for (InetAddress address : addresses) {
        if (!addressPolicy.test(address)) throw new JwksFetchException();
      }
      return addresses;
    } catch (JwksFetchException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new JwksFetchException();
    }
  }

  private static URI requireSafeUri(URI value) {
    Objects.requireNonNull(value);
    String host = value.getHost();
    if (!"https".equals(value.getScheme())
        || host == null
        || host.isEmpty()
        || value.getUserInfo() != null
        || value.getQuery() != null
        || value.getFragment() != null
        || value.getPath() == null
        || value.getPath().isEmpty()
        || value.getPath().startsWith("//")
        || value.getPort() == 0
        || value.getPort() > 65_535
        || isIpLiteral(host)
        || host.endsWith(".")
        || !canonicalHost(host).equals(host.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("JWKS URIs must be canonical HTTPS DNS URLs.");
    }
    return value.normalize();
  }

  private static String canonicalHost(String hostname) {
    try {
      return IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("The JWKS hostname is invalid.", exception);
    }
  }

  private static boolean isIpLiteral(String host) {
    if (host.indexOf(':') >= 0) return true;
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) return false;
    for (String part : parts) {
      if (part.isEmpty() || !part.chars().allMatch(Character::isDigit)) return false;
    }
    return true;
  }

  static boolean isPublicAddress(InetAddress address) {
    Objects.requireNonNull(address);
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet4Address) return isPublicIpv4(bytes);
    if (address instanceof Inet6Address) return isPublicIpv6(bytes);
    return false;
  }

  private static boolean isPublicIpv4(byte[] address) {
    int first = Byte.toUnsignedInt(address[0]);
    int second = Byte.toUnsignedInt(address[1]);
    int third = Byte.toUnsignedInt(address[2]);
    if (first == 0
        || first == 10
        || first == 127
        || first >= 224
        || (first == 100 && second >= 64 && second <= 127)
        || (first == 169 && second == 254)
        || (first == 172 && second >= 16 && second <= 31)
        || (first == 192 && second == 168)
        || (first == 198 && (second == 18 || second == 19))) {
      return false;
    }
    return !(first == 192 && second == 0 && (third == 0 || third == 2))
        && !(first == 198 && second == 51 && third == 100)
        && !(first == 203 && second == 0 && third == 113);
  }

  private static boolean isPublicIpv6(byte[] address) {
    int first = Byte.toUnsignedInt(address[0]);
    int second = Byte.toUnsignedInt(address[1]);
    int third = Byte.toUnsignedInt(address[2]);
    int fourth = Byte.toUnsignedInt(address[3]);
    boolean globalUnicast = (first & 0xe0) == 0x20;
    boolean uniqueLocal = (first & 0xfe) == 0xfc;
    boolean documentation =
        first == 0x20 && second == 0x01 && third == 0x0d && fourth == 0xb8;
    boolean transitionOrBenchmark =
        (first == 0x20 && second == 0x02)
            || (first == 0x20
                && second == 0x01
                && third == 0x00
                && (fourth == 0x00
                    || fourth == 0x02
                    || (fourth & 0xf0) == 0x10
                    || (fourth & 0xf0) == 0x20));
    boolean ipv4Mapped =
        ByteBuffer.wrap(address, 0, 12).getLong() == 0L
            && ByteBuffer.wrap(address, 8, 4).getInt() == 0xffff;
    return globalUnicast
        && !uniqueLocal
        && !documentation
        && !transitionOrBenchmark
        && !ipv4Mapped;
  }

  private static boolean isJsonContentType(String value) {
    if (value == null) return false;
    String mediaType = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return "application/json".equals(mediaType) || "application/jwk-set+json".equals(mediaType);
  }

  private static String decodeUtf8(byte[] value) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(value))
          .toString();
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new JwksFetchException();
    }
  }

  private static Duration boundedTimeout(Duration value, String field) {
    Objects.requireNonNull(value);
    if (value.isZero() || value.isNegative() || value.compareTo(MAXIMUM_TIMEOUT) > 0) {
      throw new IllegalArgumentException(field + " is outside its safe bound.");
    }
    return value;
  }
}
