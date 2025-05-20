@Component
public class ServiceNowRestClient {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceNowRestClient.class);
    
    private final RestTemplate restTemplate;
    private final TokenCachingService tokenService;
    
    @Value("${servicenow.base.url}")
    private String baseUrl;
    
    @Value("${servicenow.api.path:/api/now/table}")
    private String apiPath;
    
    public ServiceNowRestClient(@Qualifier("serviceNowRestTemplate") RestTemplate restTemplate,
                               TokenCachingService tokenService) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
    }
    
    /**
     * Generic method to call ServiceNow API
     */
    public <T> ResponseEntity<T> callServiceNowApi(String endpoint, Object requestBody, 
                                                  Class<T> responseClass) {
        return callServiceNowApi(endpoint, HttpMethod.POST, requestBody, responseClass);
    }
    
    /**
     * Generic method to call ServiceNow API with HTTP method
     */
    public <T> ResponseEntity<T> callServiceNowApi(String endpoint, HttpMethod method, 
                                                  Object requestBody, Class<T> responseClass) {
        String url = buildUrl(endpoint);
        
        try {
            // Get access token
            String accessToken = tokenService.getAccessToken();
            
            // Prepare headers
            HttpHeaders headers = createHeaders(accessToken);
            
            // Create request entity
            HttpEntity<?> requestEntity = new HttpEntity<>(requestBody, headers);
            
            // Make API call
            logger.debug("Calling ServiceNow API: {} {}", method, url);
            ResponseEntity<T> response = restTemplate.exchange(url, method, requestEntity, responseClass);
            
            logger.debug("ServiceNow API call successful: {}", response.getStatusCode());
            return response;
            
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                logger.warn("Received 401 Unauthorized, invalidating token and retrying");
                
                // Token might be expired, invalidate and retry once
                tokenService.invalidateToken();
                
                try {
                    String freshToken = tokenService.getAccessToken();
                    HttpHeaders headers = createHeaders(freshToken);
                    HttpEntity<?> requestEntity = new HttpEntity<>(requestBody, headers);
                    
                    ResponseEntity<T> retryResponse = restTemplate.exchange(url, method, requestEntity, responseClass);
                    logger.info("Retry successful after token refresh");
                    return retryResponse;
                    
                } catch (Exception retryException) {
                    logger.error("Retry failed after token refresh", retryException);
                    throw new ServiceNowApiException("API call failed after token refresh", retryException);
                }
            } else {
                logger.error("ServiceNow API call failed: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
                throw new ServiceNowApiException("ServiceNow API call failed", e);
            }
        } catch (Exception e) {
            logger.error("Unexpected error calling ServiceNow API", e);
            throw new ServiceNowApiException("ServiceNow API call failed", e);
        }
    }
    
    /**
     * Create incident
     */
    public ResponseEntity<String> createIncident(Map<String, Object> incidentData) {
        return callServiceNowApi("incident", HttpMethod.POST, incidentData, String.class);
    }
    
    /**
     * Create service request
     */
    public ResponseEntity<String> createServiceRequest(Map<String, Object> requestData) {
        return callServiceNowApi("sc_request", HttpMethod.POST, requestData, String.class);
    }
    
    /**
     * Update record
     */
    public ResponseEntity<String> updateRecord(String table, String sysId, Map<String, Object> updateData) {
        String endpoint = table + "/" + sysId;
        return callServiceNowApi(endpoint, HttpMethod.PUT, updateData, String.class);
    }
    
    /**
     * Get record
     */
    public ResponseEntity<String> getRecord(String table, String sysId) {
        String endpoint = table + "/" + sysId;
        return callServiceNowApi(endpoint, HttpMethod.GET, null, String.class);
    }
    
    /**
     * Query records with parameters
     */
    public ResponseEntity<String> queryRecords(String table, Map<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(buildUrl(table));
        
        if (queryParams != null) {
            queryParams.forEach(builder::queryParam);
        }
        
        String url = builder.toUriString();
        
        try {
            String accessToken = tokenService.getAccessToken();
            HttpHeaders headers = createHeaders(accessToken);
            HttpEntity<?> requestEntity = new HttpEntity<>(headers);
            
            return restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                // Retry with fresh token
                tokenService.invalidateToken();
                String freshToken = tokenService.getAccessToken();
                HttpHeaders headers = createHeaders(freshToken);
                HttpEntity<?> requestEntity = new HttpEntity<>(headers);
                
                return restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
            }
            throw new ServiceNowApiException("Query failed", e);
        }
    }
    
    private String buildUrl(String endpoint) {
        return baseUrl + apiPath + "/" + endpoint;
    }
    
    private HttpHeaders createHeaders(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(accessToken);
        
        // Add additional headers if needed
        headers.set("User-Agent", "Spring-RestTemplate-Client/1.0");
        
        return headers;
    }
    
    /**
     * Health check method
     */
    public boolean isServiceNowHealthy() {
        try {
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("sysparm_limit", "1");
            
            ResponseEntity<String> response = queryRecords("sys_user", queryParams);
            return response.getStatusCode().is2xxSuccessful();
            
        } catch (Exception e) {
            logger.error("ServiceNow health check failed", e);
            return false;
        }
    }
    
    /**
     * Get token information for monitoring
     */
    public TokenCachingService.AccessTokenInfo getTokenInfo() {
        return tokenService.getTokenInfo();
    }
}

// Custom exception for ServiceNow API errors
public class ServiceNowApiException extends RuntimeException {
    public ServiceNowApiException(String message) {
        super(message);
    }
    
    public ServiceNowApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
