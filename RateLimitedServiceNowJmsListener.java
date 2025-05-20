@Component
public class RateLimitedServiceNowJmsListener {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimitedServiceNowJmsListener.class);
    
    private final ServiceNowRestClient serviceNowClient;
    private final ObjectMapper objectMapper;
    
    // Single thread executor for ServiceNow API calls
    private final ExecutorService serviceNowExecutor;
    
    // Blocking queue to control rate
    private final BlockingQueue<ServiceNowRequest> requestQueue;
    
    @Value("${servicenow.rate.limit.requests.per.second:2}")
    private int requestsPerSecond;
    
    @Value("${servicenow.queue.capacity:1000}")
    private int queueCapacity;
    
    @Value("${servicenow.queue.offer.timeout:30}")
    private int queueOfferTimeoutSeconds;
    
    public RateLimitedServiceNowJmsListener(ServiceNowRestClient serviceNowClient) {
        this.serviceNowClient = serviceNowClient;
        this.objectMapper = new ObjectMapper();
        
        // Initialize blocking queue with capacity
        this.requestQueue = new LinkedBlockingQueue<>(queueCapacity);
        
        // Single thread executor for controlled processing
        this.serviceNowExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ServiceNow-RateLimit-Thread");
            t.setDaemon(false);
            return t;
        });
        
        // Start the rate-limited processor
        startRateLimitedProcessor();
        
        logger.info("Rate-limited ServiceNow JMS Listener initialized: {} req/sec, queue capacity: {}", 
                   requestsPerSecond, queueCapacity);
    }
    
    @JmsListener(destination = "incident.queue", 
                concurrency = "1-5", // JMS can still be concurrent for receiving
                containerFactory = "jmsListenerContainerFactory")
    public void handleIncidentMessage(Message message) {
        processMessageAsync(message, "incident", "Incident");
    }
    
    @JmsListener(destination = "request.queue", 
                concurrency = "1-5", // JMS can still be concurrent for receiving
                containerFactory = "jmsListenerContainerFactory")
    public void handleRequestMessage(Message message) {
        processMessageAsync(message, "sc_request", "Service Request");
    }
    
    /**
     * Async processing - adds message to queue for rate-limited processing
     */
    private void processMessageAsync(Message message, String table, String messageType) {
        try {
            String messageId = message.getJMSMessageID();
            String messageBody = extractMessageBody(message);
            
            // Create request object with RestTemplate data
            ServiceNowRequest request = new ServiceNowRequest(
                messageId, messageType, table, messageBody
            );
            
            // Add to queue (this will block if queue is full)
            boolean added = requestQueue.offer(request, queueOfferTimeoutSeconds, TimeUnit.SECONDS);
            
            if (!added) {
                logger.error("Failed to queue {} message {}: Queue is full (size: {})", 
                           messageType, messageId, requestQueue.size());
                throw new RuntimeException("ServiceNow processing queue is full");
            }
            
            logger.debug("Queued {} message {} for rate-limited processing (Queue size: {})", 
                        messageType, messageId, requestQueue.size());
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while queueing message", e);
            throw new RuntimeException("Message queueing interrupted", e);
        } catch (Exception e) {
            logger.error("Failed to queue message for processing", e);
            throw new RuntimeException("Message queueing failed", e);
        }
    }
    
    /**
     * Start the single-threaded rate-limited processor
     */
    private void startRateLimitedProcessor() {
        serviceNowExecutor.submit(() -> {
            logger.info("Starting rate-limited ServiceNow processor: {} requests/second", requestsPerSecond);
            
            long delayMs = 1000 / requestsPerSecond; // Calculate delay between requests
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Take request from queue (blocks if empty)
                    ServiceNowRequest request = requestQueue.take();
                    
                    // Process the request using RestTemplate
                    processServiceNowRequestWithRestTemplate(request);
                    
                    // Rate limiting delay
                    if (delayMs > 0) {
                        Thread.sleep(delayMs);
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("ServiceNow rate-limited processor interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in ServiceNow rate-limited processor", e);
                    // Continue processing other requests
                }
            }
            logger.info("ServiceNow rate-limited processor stopped");
        });
    }
    
    /**
     * Process ServiceNow request using RestTemplate with token caching
     */
    private void processServiceNowRequestWithRestTemplate(ServiceNowRequest request) {
        try {
            logger.info("Processing {} message: {} (Queue size: {})", 
                       request.getMessageType(), request.getMessageId(), requestQueue.size());
            
            // Parse message content
            Map<String, Object> requestData = objectMapper.readValue(request.getMessageBody(), Map.class);
            
            // Add processing metadata
            requestData.put("u_jms_message_id", request.getMessageId());
            requestData.put("u_processed_timestamp", new Date().toString());
            requestData.put("u_processing_thread", Thread.currentThread().getName());
            
            // Call ServiceNow API using RestTemplate with automatic token management
            ResponseEntity<String> response = serviceNowClient.callServiceNowApi(
                request.getTable(), HttpMethod.POST, requestData, String.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                logger.info("Successfully processed {} message: {} - Status: {}", 
                          request.getMessageType(), request.getMessageId(), response.getStatusCode());
                
                // Log response for debugging if needed
                if (logger.isDebugEnabled()) {
                    logger.debug("Response body: {}", response.getBody());
                }
            } else {
                logger.error("Unexpected response status for {} message {}: {}", 
                           request.getMessageType(), request.getMessageId(), response.getStatusCode());
                throw new RuntimeException("Unexpected response status: " + response.getStatusCode());
            }
            
        } catch (ServiceNowApiException e) {
            logger.error("ServiceNow API error processing {} message: {}", 
                        request.getMessageType(), request.getMessageId(), e);
            
            // Could implement retry logic here or add to DLQ
            handleProcessingError(request, e);
            
        } catch (JsonProcessingException e) {
            logger.error("JSON parsing error for {} message: {}", 
                        request.getMessageType(), request.getMessageId(), e);
            throw new RuntimeException("Invalid JSON message format", e);
            
        } catch (Exception e) {
            logger.error("Unexpected error processing {} message: {}", 
                        request.getMessageType(), request.getMessageId(), e);
            handleProcessingError(request, e);
        }
    }
    
    /**
     * Handle processing errors (could be extended for retry logic or DLQ)
     */
    private void handleProcessingError(ServiceNowRequest request, Exception e) {
        // For now, just log the error
        // In production, you might want to:
        // 1. Add to a Dead Letter Queue (DLQ)
        // 2. Implement retry with exponential backoff
        // 3. Send alerts
        
        logger.error("Failed to process {} message {} after rate limiting. Error: {}", 
                    request.getMessageType(), request.getMessageId(), e.getMessage());
        
        // Example: Could add to retry queue with delay
        // retryLater(request, e);
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
    
    /**
     * Get queue metrics for monitoring
     */
    public QueueMetrics getQueueMetrics() {
        return new QueueMetrics(
            requestQueue.size(),
            queueCapacity,
            requestsPerSecond,
            serviceNowExecutor.isShutdown()
        );
    }
    
    /**
     * Update rate limit dynamically
     */
    public void updateRateLimit(int newRequestsPerSecond) {
        if (newRequestsPerSecond > 0 && newRequestsPerSecond <= 100) {
            this.requestsPerSecond = newRequestsPerSecond;
            logger.info("Updated ServiceNow rate limit to {} requests/second", newRequestsPerSecond);
        } else {
            logger.warn("Invalid rate limit value: {}. Must be between 1 and 100", newRequestsPerSecond);
        }
    }
    
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down ServiceNow rate-limited processor");
        
        serviceNowExecutor.shutdown();
        try {
            // Wait for current processing to complete
            if (!serviceNowExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Rate-limited processor did not terminate gracefully, forcing shutdown");
                serviceNowExecutor.shutdownNow();
                
                // Wait a bit more for forced shutdown
                if (!serviceNowExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    logger.error("Rate-limited processor did not terminate after forced shutdown");
                }
            }
        } catch (InterruptedException e) {
            logger.warn("Interrupted during shutdown, forcing immediate shutdown");
            serviceNowExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Log remaining queue items
        int remainingItems = requestQueue.size();
        if (remainingItems > 0) {
            logger.warn("Shutdown with {} unprocessed items in queue", remainingItems);
        }
    }
    
    /**
     * Inner class for request data
     */
    private static class ServiceNowRequest {
        private final String messageId;
        private final String messageType;
        private final String table;
        private final String messageBody;
        private final long timestamp;
        
        public ServiceNowRequest(String messageId, String messageType, String table, String messageBody) {
            this.messageId = messageId;
            this.messageType = messageType;
            this.table = table;
            this.messageBody = messageBody;
            this.timestamp = System.currentTimeMillis();
        }
        
        // Getters
        public String getMessageId() { return messageId; }
        public String getMessageType() { return messageType; }
        public String getTable() { return table; }
        public String getMessageBody() { return messageBody; }
        public long getTimestamp() { return timestamp; }
        
        public long getAge() {
            return System.currentTimeMillis() - timestamp;
        }
    }
    
    /**
     * Queue metrics for monitoring
     */
    public static class QueueMetrics {
        private final int currentSize;
        private final int capacity;
        private final int requestsPerSecond;
        private final boolean processorShutdown;
        
        public QueueMetrics(int currentSize, int capacity, int requestsPerSecond, boolean processorShutdown) {
            this.currentSize = currentSize;
            this.capacity = capacity;
            this.requestsPerSecond = requestsPerSecond;
            this.processorShutdown = processorShutdown;
        }
        
        // Getters
        public int getCurrentSize() { return currentSize; }
        public int getCapacity() { return capacity; }
        public int getRequestsPerSecond() { return requestsPerSecond; }
        public boolean isProcessorShutdown() { return processorShutdown; }
        public double getUtilizationPercentage() { 
            return (double) currentSize / capacity * 100; 
        }
    }
}
