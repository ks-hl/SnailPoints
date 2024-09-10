package dev.kshl.points;

import com.sun.net.httpserver.Headers;
import dev.kshl.kshlib.exceptions.BusyException;
import dev.kshl.kshlib.net.HTTPRequestType;
import dev.kshl.kshlib.net.HTTPResponseCode;
import dev.kshl.kshlib.net.WebServer;
import dev.kshl.kshlib.net.WebServer.Request;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

public class TestWebServer {

    private PointsWebServer server;
    private SQLManager mockSqlManager;

    @BeforeEach
    public void setup() throws SQLException, IOException, ClassNotFoundException {
        mockSqlManager = spy(new SQLManager(new File("test/test.db")) {{
            init();
        }});

        EmailChallenger mockEmailChallenger = mock(EmailChallenger.class);

        server = spy(new PointsWebServer(8080, mockSqlManager, mockEmailChallenger, 0) {
            @Override
            protected long getLoginDelayTime() {
                return 300L;
            }
        });
    }

    @Test
    public void testSQLInjectionInLogin() {
        // Simulate SQL Injection attempt in the username field
        String maliciousUsername = "' OR 1=1 --";

        Request mockRequest = createMockRequest("/login", new JSONObject().put("username", maliciousUsername).put("password", "wrongpassword"));
        WebServer.WebException e = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest));
        assertEquals("Invalid username/password", e.getUserErrorMessage());

        Request mockRequest2 = createMockRequest("/login", new JSONObject().put("username", "username").put("password", maliciousUsername));
        e = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest2));
        assertEquals("Invalid username/password", e.getUserErrorMessage());

//        verify(mockSqlManager, never()).getUIDManager(); // Ensure that SQLManager is not used for this malicious input
    }

    @Test
    public void testXSSInUsernameField() {
        // Simulate XSS attempt in the username field
        String xssPayload = "<script>alert('XSS')</script>";
        Request mockRequest = createMockRequest("/createaccount", new JSONObject().put("username", xssPayload).put("password", "password").put("email", "email@email.com"));

        WebServer.WebException exception = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest));

        assertEquals(HTTPResponseCode.UNPROCESSABLE_ENTITY.getCode(), exception.responseCode.getCode());
        assertFalse(exception.getUserErrorMessage().contains(xssPayload));
    }

    @Test
    public void testBruteForceLoginAttempt() throws SQLException, BusyException {
        // Simulate multiple failed login attempts
        String username = "testuser";

        mockSqlManager.getUIDManager().getIDOpt("testuser", true);

        long start = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            Request mockRequest = createMockRequest("/login", new JSONObject().put("username", username).put("password", "wrongpassword"));
            WebServer.WebException e = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest));
            assertEquals("Invalid username/password", e.getUserErrorMessage());
        }

        // 6th attempt should trigger brute force protection
        Request mockRequest = createMockRequest("/login", new JSONObject().put("username", username).put("password", "wrongpassword"));
        WebServer.WebException exception = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest));
        assertEquals(HTTPResponseCode.FORBIDDEN.getCode(), exception.responseCode.getCode());
        assertEquals("Too many login attempts within 1 minute.", exception.getUserErrorMessage(), () -> {
            throw new RuntimeException(exception);
        });

        long timeElapsed = System.currentTimeMillis() - start;
        assertTrue(timeElapsed <= 1500 && timeElapsed >= 1200, "Between 1.2 and 1.5 seconds should have elapsed. Was " + timeElapsed + "ms.");
    }

    @Test
    public void testWeakPasswordEnforcement() throws SQLException, BusyException {
        // Test with a weak password
        String email = "test@example.com";
        mockSqlManager.getEmailWhitelistManager().add(mockSqlManager.getEmailIDManager().getIDOpt(email, true).orElseThrow());
        String weakPassword = "weak";
        Request mockRequest = createMockRequest("/createaccount", new JSONObject().put("username", "testuser").put("password", weakPassword).put("email", email));

        WebServer.WebException exception = assertThrows(WebServer.WebException.class, () -> server.handle(mockRequest));
        assertEquals(HTTPResponseCode.UNPROCESSABLE_ENTITY.getCode(), exception.responseCode.getCode());
        assertTrue(exception.getUserErrorMessage().contains("Password must be at least"), "Error: " + exception.getUserErrorMessage());
    }

    @Test
    public void testAdminCommandAuthorization() throws Exception {
        List<String> adminEndpoints = List.of("setpassword", "makedemo");
        for (String adminEndpoint : adminEndpoints) {
            Request mockRequest = createMockRequest("/" + adminEndpoint, null);
            doReturn(new PointsWebServer.AuthResult(true, 1, 1, false, true)).when(server).validateSessionCookie(mockRequest);
            assertThrows(PointsWebServer.ForbiddenAdminCommandException.class, () -> server.handle(mockRequest), "Handling endpoint " + adminEndpoint);
        }
    }

    private Request createMockRequest(String endpoint, JSONObject body) {
        return new Request(System.currentTimeMillis(), "127.0.0.1", endpoint, body == null ? HTTPRequestType.GET : HTTPRequestType.POST, new Headers(), Map.of(), body == null ? "" : body.toString(), body);
    }
}
