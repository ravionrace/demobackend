@Service
public class TokenCachingService {
    
    private static final Logger logger = LoggerFactory.getLogger(TokenCachingService.class);
    
    private final RestTemplate restTemplate;
    
    // Thread-safe token cache
    private volatile AccessTokenInfo cachedToken;
    private final Object tokenLock = new Object();
    
    @Value("${oauth.token.url}")
    private String tokenUrl;
    
    @Value("${oauth.client.id}")
    private String clientId;
    
    @Value("${oauth.client.secret}")
    private String clientSecret;
    
    @Value("${oauth.scope:#{null}}")
    private String scope;
    
    @Value("${oauth.grant.type:client_credentials}")
    private String grantType;
    
    public TokenCachingService(@Qualifier("serviceNowRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }
    
    /**
     * Get valid access token (thread-safe with caching)
     */
    public String getAccessToken() {
        // Check if current token is still valid
        if (isTokenValid(cachedToken)) {
            return cachedToken.getAccessToken();
        }
        
        // Use double-checked locking for thread safety
        synchronized (tokenLock) {
            // Check again inside synchronized block
            if (isTokenValid(cachedToken)) {
                return cachedToken.getAccessToken();
            }
            
            // Token is expired or null, fetch new one
            return fetchNewToken();
        }
    }
    
    /**
     * Force token refresh
     */
    public String refreshToken() {
        synchronized (tokenLock) {
            cachedToken = null;
            return fetchNewToken();
        }
    }
    
    /**
     * Invalidate current token
     */
    public void invalidateToken() {
        synchronized (tokenLock) {
            cachedToken = null;
        }
    }
    
    private boolean isTokenValid(AccessTokenInfo tokenInfo) {
        return tokenInfo != null && 
               tokenInfo.getAccessToken() != null &&
               System.currentTimeMillis() < (tokenInfo.getExpiresAt() - 60000); // 1 minute buffer
    }
    
    private String fetchNewToken() {
        try {
            logger.info("Fetching new OAuth token from: {}", tokenUrl);
            
            // Prepare request headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
            
            // Prepare request body
            MultiValueMap<String, String> requestBody = new LinkedMultiValueMap<>();
            requestBody.add("grant_type", grantType);
            requestBody.add("client_id", clientId);
            requestBody.add("client_secret", clientSecret);
            
            if (scope != null && !scope.isEmpty()) {
                requestBody.add("scope", scope);
            }
            
            // Create request entity
            HttpEntity<MultiValueMap<String, String>> requestEntity = 
                new HttpEntity<>(requestBody, headers);
            
            // Make token request
            ResponseEntity<TokenResponse> response = restTemplate.exchange(
                tokenUrl,
                HttpMethod.POST,
                requestEntity,
                TokenResponse.class
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                TokenResponse tokenResponse = response.getBody();
                
                if (tokenResponse != null && tokenResponse.getAccessToken() != null) {
                    // Calculate expiry time
                    long expiresAt = System.currentTimeMillis() + 
                                   (tokenResponse.getExpiresIn() * 1000L);
                    
                    // Cache the token
                    cachedToken = new AccessTokenInfo(
                        tokenResponse.getAccessToken(),
                        tokenResponse.getTokenType(),
                        tokenResponse.getExpiresIn(),
                        expiresAt,
                        tokenResponse.getScope()
                    );
                    
                    logger.info("Successfully fetched OAuth token, expires in {} seconds", 
                              tokenResponse.getExpiresIn());
                    
                    return cachedToken.getAccessToken();
                } else {
                    throw new RuntimeException("Invalid token response: missing access_token");
                }
            } else {
                throw new RuntimeException("Token request failed with status: " + response.getStatusCode());
            }
            
        } catch (HttpClientErrorException e) {
            logger.error("HTTP error fetching token: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("OAuth token fetch failed: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Error fetching OAuth token", e);
            throw new RuntimeException("OAuth token fetch failed", e);
        }
    }
    
    /**
     * Get token info for monitoring
     */
    public AccessTokenInfo getTokenInfo() {
        return cachedToken;
    }
    
    /**
     * Check if token will expire soon (within specified minutes)
     */
    public boolean willExpireSoon(int minutes) {
        if (cachedToken == null) return true;
        
        long warningTime = System.currentTimeMillis() + (minutes * 60 * 1000L);
        return cachedToken.getExpiresAt() < warningTime;
    }
    
    // Token response DTO
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;
        
        @JsonProperty("token_type")
        private String tokenType;
        
        @JsonProperty("expires_in")
        private int expiresIn;
        
        @JsonProperty("scope")
        private String scope;
        
        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        
        public int getExpiresIn() { return expiresIn; }
        public void setExpiresIn(int expiresIn) { this.expiresIn = expiresIn; }
        
        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }
    }
    
    // Token info class
    public static class AccessTokenInfo {
        private final String accessToken;
        private final String tokenType;
        private final int expiresIn;
        private final long expiresAt;
        private final String scope;
        
        public AccessTokenInfo(String accessToken, String tokenType, int expiresIn, 
                              long expiresAt, String scope) {
            this.accessToken = accessToken;
            this.tokenType = tokenType;
            this.expiresIn = expiresIn;
            this.expiresAt = expiresAt;
            this.scope = scope;
        }
        
        // Getters
        public String getAccessToken() { return accessToken; }
        public String getTokenType() { return tokenType; }
        public int getExpiresIn() { return expiresIn; }
        public long getExpiresAt() { return expiresAt; }
        public String getScope() { return scope; }
        
        public boolean isExpired() {
            return System.currentTimeMillis() >= expiresAt;
        }
        
        public long getTimeToExpiry() {
            return Math.max(0, expiresAt - System.currentTimeMillis());
        }
    }
}
