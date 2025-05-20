@RestController
public class TestController {
    
    @Autowired
    private ServiceNowRestClient serviceNowClient;
    
    @PostMapping("/test/incident")
    public ResponseEntity<String> testIncident(@RequestBody Map<String, Object> incidentData) {
        try {
            ResponseEntity<String> response = serviceNowClient.createIncident(incidentData);
            return ResponseEntity.ok("Incident created: " + response.getStatusCode());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error: " + e.getMessage());
        }
    }
    
    @GetMapping("/test/token-info")
    public ResponseEntity<Map<String, Object>> getTokenInfo() {
        TokenCachingService.AccessTokenInfo tokenInfo = serviceNowClient.getTokenInfo();
        
        Map<String, Object> info = new HashMap<>();
        if (tokenInfo != null) {
            info.put("hasToken", true);
            info.put("expiresAt", new Date(tokenInfo.getExpiresAt()));
            info.put("timeToExpiry", tokenInfo.getTimeToExpiry() / 1000 + " seconds");
            info.put("tokenType", tokenInfo.getTokenType());
        } else {
            info.put("hasToken", false);
        }
        
        return ResponseEntity.ok(info);
    }
}
