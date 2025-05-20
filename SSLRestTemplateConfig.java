@Configuration
public class SSLRestTemplateConfig {
    
    private static final Logger logger = LoggerFactory.getLogger(SSLRestTemplateConfig.class);
    
    @Value("${ssl.keystore.path}")
    private String keystorePath;
    
    @Value("${ssl.keystore.password}")
    private String keystorePassword;
    
    @Value("${ssl.truststore.path:#{null}}")
    private String truststorePath;
    
    @Value("${ssl.truststore.password:#{null}}")
    private String truststorePassword;
    
    @Bean
    @Primary
    public RestTemplate sslRestTemplate() throws Exception {
        return createRestTemplate();
    }
    
    @Bean("serviceNowRestTemplate")
    public RestTemplate serviceNowRestTemplate() throws Exception {
        return createRestTemplate();
    }
    
    private RestTemplate createRestTemplate() throws Exception {
        // Create SSL Context with client certificate
        SSLContext sslContext = createSSLContext();
        
        // Create HTTP client with SSL and connection pooling
        CloseableHttpClient httpClient = createHttpClient(sslContext);
        
        // Create request factory
        HttpComponentsClientHttpRequestFactory requestFactory = 
            new HttpComponentsClientHttpRequestFactory(httpClient);
        
        // Configure timeouts
        requestFactory.setConnectTimeout(30000);
        requestFactory.setReadTimeout(60000);
        requestFactory.setConnectionRequestTimeout(30000);
        
        // Create RestTemplate
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        
        // Add interceptors for logging (optional)
        restTemplate.setInterceptors(Arrays.asList(new LoggingClientHttpRequestInterceptor()));
        
        // Add error handler
        restTemplate.setErrorHandler(new RestTemplateErrorHandler());
        
        logger.info("SSL RestTemplate configured successfully");
        return restTemplate;
    }
    
    private SSLContext createSSLContext() throws Exception {
        // Load client keystore (JKS)
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (FileInputStream keystoreStream = new FileInputStream(keystorePath)) {
            keyStore.load(keystoreStream, keystorePassword.toCharArray());
        }
        
        // Load truststore if provided, otherwise use default
        KeyStore trustStore = null;
        if (truststorePath != null && !truststorePath.isEmpty()) {
            trustStore = KeyStore.getInstance("JKS");
            try (FileInputStream truststoreStream = new FileInputStream(truststorePath)) {
                trustStore.load(truststoreStream, truststorePassword.toCharArray());
            }
        }
        
        // Create SSL context
        SSLContextBuilder sslContextBuilder = SSLContexts.custom()
                .loadKeyMaterial(keyStore, keystorePassword.toCharArray());
        
        if (trustStore != null) {
            sslContextBuilder.loadTrustMaterial(trustStore, null);
        } else {
            // Trust all certificates (not recommended for production)
            sslContextBuilder.loadTrustMaterial(TrustAllStrategy.INSTANCE);
        }
        
        return sslContextBuilder.build();
    }
    
    private CloseableHttpClient createHttpClient(SSLContext sslContext) {
        // Connection manager with pooling
        PoolingHttpClientConnectionManager connectionManager = 
            new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(20);
        connectionManager.setDefaultSocketConfig(
            SocketConfig.custom()
                .setSoTimeout(60000)
                .build()
        );
        
        // Request configuration
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(30000)
                .setConnectTimeout(30000)
                .setSocketTimeout(60000)
                .build();
        
        // Create HTTP client
        return HttpClients.custom()
                .setSSLContext(sslContext)
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(new DefaultHttpRequestRetryHandler(3, true))
                .build();
    }
    
    // Logging interceptor
    public static class LoggingClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {
        private static final Logger logger = LoggerFactory.getLogger(LoggingClientHttpRequestInterceptor.class);
        
        @Override
        public ClientHttpResponse intercept(
                HttpRequest request, 
                byte[] body, 
                ClientHttpRequestExecution execution) throws IOException {
            
            logger.debug("Request: {} {}", request.getMethod(), request.getURI());
            
            ClientHttpResponse response = execution.execute(request, body);
            
            logger.debug("Response: {} {}", response.getStatusCode(), response.getStatusText());
            
            return response;
        }
    }
    
    // Custom error handler
    public static class RestTemplateErrorHandler implements ResponseErrorHandler {
        private static final Logger logger = LoggerFactory.getLogger(RestTemplateErrorHandler.class);
        
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().series() == HttpStatus.Series.CLIENT_ERROR ||
                   response.getStatusCode().series() == HttpStatus.Series.SERVER_ERROR;
        }
        
        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            String responseBody = StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8);
            
            logger.error("HTTP Error: {} {} - Response: {}", 
                        response.getStatusCode(), 
                        response.getStatusText(), 
                        responseBody);
            
            throw new HttpClientErrorException(
                response.getStatusCode(), 
                response.getStatusText(), 
                responseBody.getBytes(), 
                StandardCharsets.UTF_8
            );
        }
    }
}
