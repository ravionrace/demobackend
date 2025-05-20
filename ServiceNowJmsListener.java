@Component
public class ServiceNowJmsListener {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceNowJmsListener.class);
    
    private final ServiceNowRestClient serviceNowClient;
    private final ObjectMapper objectMapper;
    
    public ServiceNowJmsListener(ServiceNowRestClient serviceNowClient) {
        this.serviceNowClient = serviceNowClient;
        this.objectMapper = new ObjectMapper();
    }
    
    @JmsListener(destination = "incident.queue", 
                concurrency = "1-3", // Controlled concurrency for rate limiting
                containerFactory = "jmsListenerContainerFactory")
    public void handleIncidentMessage(Message message) {
        processMessage(message, "incident", "Incident");
    }
    
    @JmsListener(destination = "request.queue", 
                concurrency = "1-3", // Controlled concurrency for rate limiting
                containerFactory = "jmsListenerContainerFactory")
    public void handleRequestMessage(Message message) {
        processMessage(message, "sc_request", "Service Request");
    }
    
    private void processMessage(Message message, String table, String messageType) {
        String messageId = null;
        
        try {
            // Extract message details
            messageId = message.getJMSMessageID();
            String messageBody = extractMessageBody(message);
            
            logger.info("Processing {} message: {}", messageType, messageId);
            
            // Parse message content
            Map<String, Object> requestData = objectMapper.readValue(messageBody, Map.class);
            
            // Add message metadata
            requestData.put("u_jms_message_id", messageId);
            requestData.put("u_processed_timestamp", new Date().toString());
            
            // Call ServiceNow API using RestTemplate with token caching
            ResponseEntity<String> response = serviceNowClient.callServiceNowApi(
                table, HttpMethod.POST, requestData, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully processed {} message: {} - Response: {}", 
                          messageType, messageId, response.getStatusCode());
                
                // Log response if needed for debugging
                if (logger.isDebugEnabled()) {
                    logger.debug("Response body: {}", response.getBody());
                }
            } else {
                logger.error("Unexpected response status for {} message {}: {}", 
                           messageType, messageId, response.getStatusCode());
                throw new RuntimeException("Unexpected response status: " + response.getStatusCode());
            }
            
        } catch (ServiceNowApiException e) {
            logger.error("ServiceNow API error processing {} message: {}", messageType, messageId, e);
            throw new RuntimeException("ServiceNow API call failed", e);
            
        } catch (JsonProcessingException e) {
            logger.error("Failed to parse JSON for {} message: {}", messageType, messageId, e);
            throw new RuntimeException("Invalid JSON message format", e);
            
        } catch (Exception e) {
            logger.error("Unexpected error processing {} message: {}", messageType, messageId, e);
            throw new RuntimeException("Message processing failed", e);
        }
    }
    
    private String extractMessageBody(Message message) throws JMSException {
        if (message instanceof TextMessage) {
            return ((TextMessage) message).getText();
        } else if (message instanceof BytesMessage) {
            BytesMessage bytesMessage = (BytesMessage) message;
            byte[] bytes = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass());
        }
    }
}

// Health check and monitoring
@Component
public class ServiceNowMonitoringService {
    
    private static final Logger logger = LoggerFactory.getLogger(ServiceNowMonitoringService.class);
    
    private final ServiceNowRestClient serviceNowClient;
    
    public ServiceNowMonitoringService(ServiceNowRestClient serviceNowClient) {
        this.serviceNowClient = serviceNowClient;
    }
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void logTokenStatus() {
        try {
            TokenCachingService.AccessTokenInfo tokenInfo = serviceNowClient.getTokenInfo();
            if (tokenInfo != null) {
                long timeToExpiry = tokenInfo.getTimeToExpiry();
                logger.info("OAuth Token Status - Time to expiry: {} minutes", 
                          timeToExpiry / (1000 * 60));
                
                // Warn if token expires soon
                if (timeToExpiry < 300000) { // 5 minutes
                    logger.warn("OAuth token will expire soon: {} seconds", timeToExpiry / 1000);
                }
            } else {
                logger.warn("No OAuth token cached");
            }
        } catch (Exception e) {
            logger.error("Error checking token status", e);
        }
    }
    
    @Scheduled(fixedRate = 600000) // Every 10 minutes
    public void healthCheck() {
        try {
            boolean healthy = serviceNowClient.isServiceNowHealthy();
            if (healthy) {
                logger.debug("ServiceNow health check: OK");
            } else {
                logger.error("ServiceNow health check: FAILED");
            }
        } catch (Exception e) {
            logger.error("ServiceNow health check error", e);
        }
    }
}
