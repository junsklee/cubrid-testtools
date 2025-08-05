/**
 * Example callback receiver for testing bisect results
 */
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class TestCallbackReceiver {
    
    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/bisect/result", new CallbackHandler());
        server.setExecutor(null);
        server.start();
        
        // Get actual IP address
        String localIp = "localhost";
        try {
            Process process = Runtime.getRuntime().exec("hostname -I");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                localIp = line.trim().split("\\s+")[0];
            }
        } catch (Exception e) {
            // Fall back to localhost if we can't get the IP
        }
        
        System.out.println("Bisect callback receiver listening on port " + port);
        System.out.println("Endpoints:");
        System.out.println("  Local:    http://localhost:" + port + "/bisect/result");
        System.out.println("  Network:  http://" + localIp + ":" + port + "/bisect/result");
        System.out.println("\nWaiting for bisect results...");
        System.out.println("(This will wait indefinitely until a request is received or Ctrl+C is pressed)");
    }
    
    static class CallbackHandler implements HttpHandler {
        // Expected results from the original script
        private static final Map<String, String> EXPECTED_RESULTS = new HashMap<>();
        static {
            EXPECTED_RESULTS.put("shell/_06_issues/_12_2h/bug_bts_7583/cases/bug_bts_7583.sh", 
                "437cc038cbfb9d98ca9777ffca22cb363dce9ce8");
            EXPECTED_RESULTS.put("shell/_06_issues/_14_1h/bug_bts_13331/cases/bug_bts_13331.sh", 
                "07afc09e091c6943e51866ef08f2d4b6cf13f16d");
            EXPECTED_RESULTS.put("shell/_06_issues/_17_1h/cbrd_20759/hide_utls_cub_admin_unloaddb_password/cases/hide_utls_cub_admin_unloaddb_password.sh", 
                "e4c81278e30c41bce34e6472b33e15b60bac0269");
            EXPECTED_RESULTS.put("shell/_28_features_844/issue_10709_statistic/issue_10709_statistic_3/cases/issue_10709_statistic_3.sh", 
                "437cc038cbfb9d98ca9777ffca22cb363dce9ce8");
            EXPECTED_RESULTS.put("shell/_37_elderberry/cbrd_23839/cases/cbrd_23839.sh", 
                "bc1dcd4e1d45b0333b71b3aab7fc7d30486e9b3f");
            EXPECTED_RESULTS.put("shell/_10_plcsql/cbrd_25619/cases/cbrd_25619.sh", 
                "7d4ab76f70aa69c781744cb4fbdf66150707eeb5");
            EXPECTED_RESULTS.put("shell/_39_fig_cake/cbrd_24046/cases/cbrd_24046.sh", 
                "437cc038cbfb9d98ca9777ffca22cb363dce9ce8");
            EXPECTED_RESULTS.put("shell/_39_fig_cake/cbrd_25035/cases/cbrd_25035.sh", 
                "604c595603d90b2ca8da4aa019e0d4964ace88b4");
            EXPECTED_RESULTS.put("shell/_39_fig_cake/cbrd_25230/cases/cbrd_25230.sh", 
                "437cc038cbfb9d98ca9777ffca22cb363dce9ce8");
            EXPECTED_RESULTS.put("shell/_39_fig_cake/cbrd_25395/cte/cases/cte.sh", 
                "604c595603d90b2ca8da4aa019e0d4964ace88b4");
        }
        
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            
            try {
                // Read request body
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(exchange.getRequestBody()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
                
                // Parse JSON
                JSONObject result = new JSONObject(body.toString());
                
                String separator = new String(new char[80]).replace('\0', '=');
                System.out.println("\n" + separator);
                System.out.println("Received bisect results at " + new Date());
                System.out.println(separator);
                System.out.println("Bad commit range: " + result.getString("firstBadCommit") + 
                                 "..." + result.getString("lastBadCommit"));
                System.out.println("Worker IP: " + result.getString("workerIp"));
                System.out.println("Generated at: " + result.getString("generatedAt"));
                System.out.println("\nTest Results:");
                System.out.println(separator);
                
                JSONArray tests = result.getJSONArray("tests");
                for (int i = 0; i < tests.length(); i++) {
                    JSONObject test = tests.getJSONObject(i);
                    System.out.println("\nTest: " + test.getString("name"));
                    System.out.println("Status: " + test.getString("status"));
                    
                    if ("found".equals(test.getString("status"))) {
                        String firstBadCommit = test.getString("firstBadCommit");
                        System.out.println("First bad commit: " + firstBadCommit);
                        System.out.println("Author: " + test.getString("author"));
                        
                        // Check against expected result
                        String testName = test.getString("name");
                        if (EXPECTED_RESULTS.containsKey(testName)) {
                            String expected = EXPECTED_RESULTS.get(testName);
                            if (firstBadCommit.startsWith(expected.substring(0, 8))) {
                                System.out.println("✓ Matches expected result");
                            } else {
                                System.out.println("✗ Expected: " + expected);
                            }
                        }
                    } else if ("error".equals(test.getString("status"))) {
                        System.out.println("Error: " + test.optString("error", "Unknown error"));
                    }
                    
                    double runtimeSeconds = test.getInt("runtimeMs") / 1000.0;
                    System.out.printf("Runtime: %.1f seconds%n", runtimeSeconds);
                }
                
                System.out.println("\n" + separator + "\n");
                
                // Send success response
                exchange.sendResponseHeaders(200, 2);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write("OK".getBytes());
                }
                
            } catch (Exception e) {
                System.err.println("Error processing result: " + e.getMessage());
                e.printStackTrace();
                
                exchange.sendResponseHeaders(400, -1);
            }
        }
    }
}
