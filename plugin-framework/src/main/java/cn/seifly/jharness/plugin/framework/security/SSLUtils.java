package cn.seifly.jharness.plugin.framework.security;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.*;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * SSL 工具类。
 *
 * <p>由 SDK 共享，供 workflow-plugin 与 channel-plugin 解耦。
 */
@Slf4j
public class SSLUtils {

    private static final X509TrustManager TRUST_ALL_MANAGER = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    public static X509TrustManager getTrustAllManager() {
        log.warn("Using trust-all TrustManager - certificates will NOT be verified. " +
                "This is insecure and should only be used in dev/intranet environments.");
        return TRUST_ALL_MANAGER;
    }

    public static SSLSocketFactory getTrustAllSSLSocketFactory() {
        log.warn("Using trust-all SSLSocketFactory - certificates will NOT be verified. " +
                "This is insecure and should only be used in dev/intranet environments.");
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{TRUST_ALL_MANAGER}, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create trust-all SSLSocketFactory", e);
        }
    }

    public static HostnameVerifier getTrustAllHostnameVerifier() {
        log.warn("Using trust-all HostnameVerifier - hostname will NOT be verified.");
        return (hostname, session) -> true;
    }

    public static SSLSocketFactory getDefaultSSLSocketFactory() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, null, null);
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create default SSLSocketFactory", e);
        }
    }

    public static X509TrustManager getDefaultTrustManager() {
        try {
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory
                    .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            for (javax.net.ssl.TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    return (X509TrustManager) tm;
                }
            }
            throw new RuntimeException("No X509TrustManager found in default TrustManagerFactory");
        } catch (Exception e) {
            throw new RuntimeException("Failed to get default X509TrustManager", e);
        }
    }
}
