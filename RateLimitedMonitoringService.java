@Component
public class RateLimitedMonitoringService {
    
    private static final Logger logger = LoggerFactory.getLogger(RateLimitedMonitoringService.class);
    
    private final RateLimitedServiceNowJmsListener rateLimitedListener;
    private final ServiceNowRestClient serviceNowClient;
    
    // Metrics tracking
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private volatile long lastResetTime = System.currentTimeMillis();
    
    public RateLimitedMonitoringService(RateLimitedServiceNowJmsListener rateLimitedListener,
                                      ServiceNowRestClient serviceNowClient) {
        this.rateLimitedListener = rateLimitedListener;
        this.serviceNowClient = serviceNowClient;
    }
    
    @Scheduled(fixedRate = 60000) // Every minute
    public void logQueueMetrics() {
        try {
            RateLimitedServiceNowJmsListener.QueueMetrics metrics = rateLimitedListener.getQueueMetrics();
            
            logger.info("ServiceNow Rate-Limited Queue Metrics - " +
                       "Size: {}/{} ({:.1f}%), " +
                       "Rate: {} req/sec, " +
                       "Processor Status: {}", 
                       metrics.getCurrentSize(),
                       metrics.getCapacity(),
                       metrics.getUtilizationPercentage(),
                       metrics.getRequestsPerSecond(),
                       metrics.isProcessorShutdown() ? "SHUTDOWN" : "RUNNING");
            
            // Alert if queue is getting full
            if (metrics.getUtilizationPercentage() > 80) {
                logger.warn("ServiceNow queue utilization is high: {:.1f}% - Consider increasing rate limit or capacity",
                           metrics.getUtilizationPercentage());
            }
            
        } catch (Exception e) {
            logger.error("Error getting queue metrics", e);
        }
    }
    
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void logTokenAndHealthStatus() {
        try {
            // Token status
            TokenCachingService.AccessTokenInfo tokenInfo = serviceNowClient.getTokenInfo();
            if (tokenInfo != null) {
                long timeToExpiry = tokenInfo.getTimeToExpiry();
                logger.info("OAuth Token Status - Time to expiry: {} minutes", 
                          timeToExpiry / (1000 * 60));
                
                // Warn if token expires soon
                if (timeToExpiry < 600000) { // 10 minutes
                    logger.warn("OAuth token will expire soon: {} minutes", timeToExpiry / (1000 * 60));
                }
            } else {
                logger.warn("No OAuth token cached");
            }
            
            // Health check
            boolean healthy = serviceNowClient.isServiceNowHealthy();
            logger.info("ServiceNow Health Check: {}", healthy ? "HEALTHY" : "UNHEALTHY");
            
        } catch (Exception e) {
            logger.error("Error during health check", e);
        }
    }
    
    @Scheduled(fixedRate = 900000) // Every 15 minutes
    public void logProcessingStats() {
        try {
            long currentTime = System.currentTimeMillis();
            long timePeriod = currentTime - lastResetTime;
            
            long processed = totalProcessed.get();
            long errors = totalErrors.get();
            
            if (timePeriod > 0) {
                double processingRate = (double) processed / (timePeriod / 1000.0 / 60.0); // per minute
                double errorRate = processed > 0 ? (double) errors / processed * 100 : 0;
                
                logger.info("ServiceNow Processing Stats (last {} minutes) - " +
                           "Processed: {}, " +
                           "Errors: {}, " +
                           "Rate: {:.2f} msg/min, " +
                           "Error Rate: {:.2f}%",
                           timePeriod / (1000 * 60),
                           processed,
                           errors,
                           processingRate,
                           errorRate);
            }
            
            // Reset counters
            totalProcessed.set(0);
            totalErrors.set(0);
            lastResetTime = currentTime;
            
        } catch (Exception e) {
            logger.error("Error logging processing stats", e);
        }
    }
    
    /**
     * Method to be called when a message is successfully processed
     */
    public void recordSuccessfulProcessing() {
        totalProcessed.incrementAndGet();
    }
    
    /**
     * Method to be called when a message processing fails
     */
    public void recordProcessingError() {
        totalErrors.incrementAndGet();
    }
    
    /**
     * Endpoint to get current metrics via REST API
     */
    @GetMapping("/monitoring/servicenow/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        Map<String, Object> metricsData = new HashMap<>();
        
        try {
            // Queue metrics
            RateLimitedServiceNowJmsListener.QueueMetrics queueMetrics = rateLimitedListener.getQueueMetrics();
            Map<String, Object> queueData = new HashMap<>();
            queueData.put("currentSize", queueMetrics.getCurrentSize());
            queueData.put("capacity", queueMetrics.getCapacity());
            queueData.put("utilizationPercentage", queueMetrics.getUtilizationPercentage());
            queueData.put("requestsPerSecond", queueMetrics.getRequestsPerSecond());
            queueData.put("processorRunning", !queueMetrics.isProcessorShutdown());
            
            // Token info
            TokenCachingService.AccessTokenInfo tokenInfo = serviceNowClient.getTokenInfo();
            Map<String, Object> tokenData = new HashMap<>();
            if (tokenInfo != null) {
                tokenData.put("hasToken", true);
                tokenData.put("timeToExpiryMinutes", tokenInfo.getTimeToExpiry() / (1000 * 60));
                tokenData.put("tokenType", tokenInfo.getTokenType());
                tokenData.put("willExpireSoon", tokenInfo.getTimeToExpiry() < 600000);
            } else {
                tokenData.put("hasToken", false);
            }
            
            // Processing stats
            Map<String, Object> processingData = new HashMap<>();
            processingData.put("totalProcessed", totalProcessed.get());
            processingData.put("totalErrors", totalErrors.get());
            
            // Health status
            boolean healthy = serviceNowClient.isServiceNowHealthy();
            
            metricsData.put("queue", queueData);
            metricsData.put("token", tokenData);
            metricsData.put("processing", processingData);
            metricsData.put("healthy", healthy);
            metricsData.put("timestamp", new Date());
            
            return ResponseEntity.ok(metricsData);
            
        } catch (Exception e) {
            logger.error("Error getting metrics", e);
            metricsData.put("error", "Failed to get metrics: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(metricsData);
        }
    }
    
    /**
     * Endpoint to update rate limit dynamically
     */
    @PostMapping("/monitoring/servicenow/rate-limit")
    public ResponseEntity<Map<String, Object>> updateRateLimit(@RequestParam int requestsPerSecond) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            rateLimitedListener.updateRateLimit(requestsPerSecond);
            
            response.put("success", true);
            response.put("message", "Rate limit updated to " + requestsPerSecond + " requests/second");
            response.put("newRateLimit", requestsPerSecond);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Error updating rate limit", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }
}
